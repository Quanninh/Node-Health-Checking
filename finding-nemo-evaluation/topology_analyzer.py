"""
Topology Analyzer module for FINDING NEMO evaluation.
Implements the exact convergence criteria and structural validation rules
defined in document/main.pdf, document/sections/ch6-evaluation.tex, and evaluation/evaluation.py.
"""

from collections import deque
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set


@dataclass
class TopologyAnalysis:
    ok: bool
    is_converged: bool
    details: str
    expected_count: int
    actual_count: int
    deficient_nodes: List[str] = field(default_factory=list)
    overfull_nodes: List[str] = field(default_factory=list)
    duplicate_nodes: List[str] = field(default_factory=list)
    self_edge_nodes: List[str] = field(default_factory=list)
    stale_edge_nodes: List[str] = field(default_factory=list)
    non_mutual_nodes: List[str] = field(default_factory=list)
    missing_nodes: List[str] = field(default_factory=list)
    unexpected_nodes: List[str] = field(default_factory=list)
    connected_components: int = 1
    problem_owners: List[str] = field(default_factory=list)


class TopologyAnalyzer:
    """
    Analyzes overlay graph snapshots against formal convergence criteria.
    """

    @staticmethod
    def analyze(
        neighbor_map: Dict[str, List[str]],
        expected_active_ids: Set[str],
        target_degree: int,
        up_node_ids: Optional[Set[str]] = None,
    ) -> TopologyAnalysis:
        """
        Validates the topology against expected degree, bidirectionality, and absence of violations.

        :param neighbor_map: Dict mapping node_id -> list of neighbor IDs
        :param expected_active_ids: Set of IDs expected to be actively running in the network
        :param target_degree: Configured degree k (or expected degree)
        :param up_node_ids: Optional set of nodes reported as 'UP' by dashboard (defaults to keys of neighbor_map)
        """
        if up_node_ids is None:
            up_node_ids = set(neighbor_map.keys())

        missing_nodes = sorted(expected_active_ids - up_node_ids)
        unexpected_nodes = sorted(up_node_ids - expected_active_ids)

        problem_owners: Set[str] = set()
        deficient_nodes: Set[str] = set()
        overfull_nodes: Set[str] = set()
        duplicate_nodes: Set[str] = set()
        self_edge_nodes: Set[str] = set()
        stale_edge_nodes: Set[str] = set()
        non_mutual_nodes: Set[str] = set()

        if missing_nodes:
            problem_owners.update(missing_nodes)

        n_expected = len(expected_active_ids)
        # Small network rule: if N <= k + 1, each node connects to all other N - 1 nodes.
        # When N == k + 1, each node has exactly k neighbors.
        # When N > k + 1, each node must have exactly k neighbors.
        effective_expected_degree = min(target_degree, max(0, n_expected - 1))

        for node_id in expected_active_ids:
            neighbors = neighbor_map.get(node_id, [])

            # 1. Degree checks
            if len(neighbors) < effective_expected_degree:
                deficient_nodes.add(node_id)
                problem_owners.add(node_id)
            elif len(neighbors) > effective_expected_degree:
                overfull_nodes.add(node_id)
                problem_owners.add(node_id)

            # 2. Duplicate neighbor check
            if len(neighbors) != len(set(neighbors)):
                duplicate_nodes.add(node_id)
                problem_owners.add(node_id)

            # 3. Self edge check
            if node_id in neighbors:
                self_edge_nodes.add(node_id)
                problem_owners.add(node_id)

            # 4. Stale edge check (references non-expected or dead nodes)
            invalid_neighbors = [nb for nb in neighbors if nb not in expected_active_ids]
            if invalid_neighbors:
                stale_edge_nodes.add(node_id)
                problem_owners.add(node_id)

        # 5. Non-mutual edge check (bidirectionality: v in N(u) <=> u in N(v))
        for node_id in expected_active_ids:
            neighbors = neighbor_map.get(node_id, [])
            for nb in neighbors:
                if nb in expected_active_ids:
                    nb_neighbors = neighbor_map.get(nb, [])
                    if node_id not in nb_neighbors:
                        non_mutual_nodes.add(node_id)
                        non_mutual_nodes.add(nb)
                        problem_owners.add(node_id)
                        problem_owners.add(nb)

        # 6. Connectivity check (BFS over expected active nodes)
        connected_components = 0
        if expected_active_ids:
            visited: Set[str] = set()
            for root in sorted(expected_active_ids):
                if root not in visited:
                    connected_components += 1
                    queue = deque([root])
                    visited.add(root)
                    while queue:
                        curr = queue.popleft()
                        for nb in neighbor_map.get(curr, []):
                            if nb in expected_active_ids and nb not in visited:
                                visited.add(nb)
                                queue.append(nb)

        violations: List[str] = []
        if missing_nodes:
            violations.append(f"missing={len(missing_nodes)}")
        if deficient_nodes:
            violations.append(f"deficient={len(deficient_nodes)}")
        if overfull_nodes:
            violations.append(f"overfull={len(overfull_nodes)}")
        if duplicate_nodes:
            violations.append(f"duplicate={len(duplicate_nodes)}")
        if self_edge_nodes:
            violations.append(f"self_edge={len(self_edge_nodes)}")
        if stale_edge_nodes:
            violations.append(f"stale_edge={len(stale_edge_nodes)}")
        if non_mutual_nodes:
            violations.append(f"non_mutual={len(non_mutual_nodes)}")
        if connected_components > 1:
            violations.append(f"partitions={connected_components}")

        is_converged = len(violations) == 0 and len(problem_owners) == 0 and connected_components <= 1

        details = "converged" if is_converged else "; ".join(violations)

        return TopologyAnalysis(
            ok=is_converged,
            is_converged=is_converged,
            details=details,
            expected_count=len(expected_active_ids),
            actual_count=len(up_node_ids),
            deficient_nodes=sorted(deficient_nodes),
            overfull_nodes=sorted(overfull_nodes),
            duplicate_nodes=sorted(duplicate_nodes),
            self_edge_nodes=sorted(self_edge_nodes),
            stale_edge_nodes=sorted(stale_edge_nodes),
            non_mutual_nodes=sorted(non_mutual_nodes),
            missing_nodes=missing_nodes,
            unexpected_nodes=unexpected_nodes,
            connected_components=connected_components,
            problem_owners=sorted(problem_owners),
        )