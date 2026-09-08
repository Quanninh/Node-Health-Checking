"""
Discrete-Event Simulation of the FINDING NEMO Protocol.
Directly implements the algorithmic rules from JoinPlanner.java and
concurrency vulnerability models from finding-nemo.tex.
"""

import math
import random
from typing import Dict, List, Optional, Set, Tuple

from config import EvaluationConfig
from topology_analyzer import TopologyAnalysis, TopologyAnalyzer


class NemoSimulatedNode:
    def __init__(self, node_id: str, k: int) -> None:
        self.node_id = node_id
        self.k = k
        self.neighbors: Set[str] = set()

    def degree(self) -> int:
        return len(self.neighbors)

    def is_sufficient(self) -> bool:
        return self.degree() >= self.k

    def add_neighbor(self, other_id: str) -> bool:
        if other_id == self.node_id:
            return False
        if len(self.neighbors) >= self.k and other_id not in self.neighbors:
            return False
        self.neighbors.add(other_id)
        return True

    def remove_neighbor(self, other_id: str) -> bool:
        if other_id in self.neighbors:
            self.neighbors.remove(other_id)
            return True
        return False


class NemoSimulator:
    """
    Simulates FINDING NEMO overlay formation, sequential joins, and concurrent bursts.
    """

    def __init__(self, config: EvaluationConfig) -> None:
        self.config = config
        self.nodes: Dict[str, NemoSimulatedNode] = {}
        self.active_ids: Set[str] = set()

    def reset(self) -> None:
        self.nodes.clear()
        self.active_ids.clear()

    def snapshot_neighbors(self) -> Dict[str, List[str]]:
        return {nid: sorted(list(node.neighbors)) for nid, node in self.nodes.items()}

    def analyze_topology(self, target_k: int) -> TopologyAnalysis:
        neighbor_map = self.snapshot_neighbors()
        return TopologyAnalyzer.analyze(
            neighbor_map=neighbor_map,
            expected_active_ids=self.active_ids,
            target_degree=target_k,
        )

    # -------------------------------------------------------------------------
    # Join Logic (Matching JoinPlanner.java)
    # -------------------------------------------------------------------------

    def join_single_node(
        self,
        new_node_id: str,
        target_k: int,
    ) -> bool:
        """
        Simulates a single node joining the overlay sequentially.
        """
        new_node = NemoSimulatedNode(new_node_id, target_k)
        self.nodes[new_node_id] = new_node
        self.active_ids.add(new_node_id)

        # 1. Discover peers (no packet loss, pure algorithm)
        discovered_ids = [nid for nid in self.active_ids if nid != new_node_id]

        if not discovered_ids:
            # First node or completely isolated by loss
            return True

        # 2. Classify discovered nodes into sufficient and deficient (JoinPlanner.java)
        sufficient_nodes = [nid for nid in discovered_ids if self.nodes[nid].degree() >= target_k]
        deficient_nodes = [nid for nid in discovered_ids if self.nodes[nid].degree() < target_k]
        random.shuffle(sufficient_nodes)
        random.shuffle(deficient_nodes)

        # 3. JoinPlanner decision
        # If deficient.size() + sufficient.size() * 2 <= maxNeighbors -> Small Network direct join
        if len(deficient_nodes) + len(sufficient_nodes) * 2 <= target_k:
            # Connect directly to all discovered nodes
            for target_id in deficient_nodes + sufficient_nodes:
                target_node = self.nodes[target_id]
                if target_node.degree() < target_k and new_node.degree() < target_k:
                    target_node.add_neighbor(new_node_id)
                    new_node.add_neighbor(target_id)
            return True

        # Otherwise: Scaled network join-and-evict
        even_deficient_targets = len(deficient_nodes)
        if even_deficient_targets % 2 == 1:
            even_deficient_targets -= 1
        even_deficient_targets = min(even_deficient_targets, target_k)

        sufficient_targets_count = min(
            (target_k - even_deficient_targets) // 2,
            len(sufficient_nodes),
        )
        deficient_targets_count = target_k - 2 * sufficient_targets_count

        chosen_deficient = deficient_nodes[:deficient_targets_count]
        chosen_sufficient = sufficient_nodes[:sufficient_targets_count]

        direct_targets = chosen_deficient + chosen_sufficient

        # Connect with deficient targets directly
        for target_id in chosen_deficient:
            target_node = self.nodes[target_id]
            if target_node.degree() < target_k and new_node.degree() < target_k:
                target_node.add_neighbor(new_node_id)
                new_node.add_neighbor(target_id)

        # For sufficient targets: execute join-and-evict
        selected_victims: Set[str] = set(direct_targets)
        selected_victims.add(new_node_id)

        for target_id in chosen_sufficient:
            target_node = self.nodes[target_id]
            # Pick a neighbor to evict
            candidate_neighbors = [nb for nb in target_node.neighbors if nb not in selected_victims]
            if not candidate_neighbors:
                continue
            victim_id = random.choice(candidate_neighbors)
            selected_victims.add(victim_id)
            victim_node = self.nodes[victim_id]

            # Execute atomic rewire:
            # 1. Break edge between target and victim
            target_node.remove_neighbor(victim_id)
            victim_node.remove_neighbor(target_id)

            # 2. Connect target <-> new_node
            target_node.add_neighbor(new_node_id)
            new_node.add_neighbor(target_id)

            # 3. Connect victim <-> new_node
            victim_node.add_neighbor(new_node_id)
            new_node.add_neighbor(victim_id)

        return True

    # -------------------------------------------------------------------------
    # Test 1: Small Network Execution
    # -------------------------------------------------------------------------

    def run_test1_trial(self, k: int) -> Tuple[bool, TopologyAnalysis]:
        """
        Test 1: Start empty, sequentially add k + 1 nodes into empty space.
        Returns (converged, analysis).
        """
        self.reset()
        for i in range(k + 1):
            node_id = f"node_{i:03d}"
            self.join_single_node(node_id, target_k=k)

        analysis = self.analyze_topology(target_k=k)
        return analysis.is_converged, analysis

    # -------------------------------------------------------------------------
    # Test 2: Scaled Network Execution (Chained)
    # -------------------------------------------------------------------------

    def run_test2_trial(self, k: int, g: int) -> Tuple[bool, TopologyAnalysis]:
        """
        Test 2: Continues from the current state (expected k+1 nodes).
        Sequentially add g consecutive nodes joining one after another.
        Returns (converged, analysis).
        """
        # Note: WE DO NOT RESET HERE. We chain from Test 1's state.
        start_idx = len(self.active_ids)
        # Consecutively add g nodes one after another
        for j in range(g):
            node_id = f"join_{start_idx + j:03d}"
            self.join_single_node(node_id, target_k=k)

        analysis = self.analyze_topology(target_k=k)
        return analysis.is_converged, analysis

    # -------------------------------------------------------------------------
    # Test 3: Concurrent Burst Execution (Chained)
    # -------------------------------------------------------------------------

    def run_test3_trial(self, k: int, burst_size_s: int) -> Tuple[bool, TopologyAnalysis]:
        """
        Test 3: Continues from current state (expected k+1+g nodes).
        Inject a simultaneous burst of s nodes into the system.
        Returns (converged, analysis).
        """
        # Note: WE DO NOT RESET HERE. We chain from Test 2's state.
        start_idx = len(self.active_ids)
        
        # Now inject burst of s nodes concurrently
        joining_ids = [f"burst_{start_idx + j:03d}" for j in range(burst_size_s)]
        for nid in joining_ids:
            self.nodes[nid] = NemoSimulatedNode(nid, k)
            self.active_ids.add(nid)

        # Baseline members available for selection (nodes before the burst)
        existing_target_pool = [nid for nid in self.nodes if nid not in joining_ids]

        # Each joining node independently creates a join plan based on pre-burst view
        # Targets need k/2 direct contacts.
        # In a simultaneous burst within window tau, nodes pick targets independently:
        target_selections: Dict[str, List[str]] = {}
        for nid in joining_ids:
            needed_direct = k // 2
            if len(existing_target_pool) >= needed_direct:
                selected = random.sample(existing_target_pool, needed_direct)
            else:
                selected = list(existing_target_pool)
            target_selections[nid] = selected

        # Commits arrive at targets (first come, first served / capacity limit)
        # Each target can only accommodate 1 new join-and-evict operation at a time.
        # If two or more joining nodes choose the same target, only one succeeds; others conflict.
        # This matches the Poisson collision model P(X >= 2) from Section 5.
        target_commit_lock: Dict[str, str] = {}  # target_id -> granted_to_joining_id

        # Randomize arrival ordering within the vulnerability window
        commit_events: List[Tuple[str, str]] = []  # (joining_id, target_id)
        for j_id, t_list in target_selections.items():
            for t_id in t_list:
                commit_events.append((j_id, t_id))
        random.shuffle(commit_events)

        # Process commits
        for j_id, t_id in commit_events:
            j_node = self.nodes[j_id]
            t_node = self.nodes[t_id]

            if j_node.degree() >= k:
                continue

            # Check if target is already locked by a concurrent join
            if t_id in target_commit_lock and target_commit_lock[t_id] != j_id:
                # Concurrent bootstrap collision! Conflict: commit rejected.
                continue

            # Attempt join-and-evict with target
            candidate_victims = [nb for nb in t_node.neighbors if nb != j_id and not nb.startswith("burst_")]
            if not candidate_victims:
                continue
            victim_id = random.choice(candidate_victims)
            victim_node = self.nodes[victim_id]

            # Lock target for this joining node during window
            target_commit_lock[t_id] = j_id

            # Rewire
            t_node.remove_neighbor(victim_id)
            victim_node.remove_neighbor(t_id)

            t_node.add_neighbor(j_id)
            j_node.add_neighbor(t_id)

            victim_node.add_neighbor(j_id)
            j_node.add_neighbor(victim_id)

        analysis = self.analyze_topology(target_k=k)
        return analysis.is_converged, analysis
