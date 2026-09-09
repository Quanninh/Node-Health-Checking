import heapq
import random
import sys

class Simulation:
    def __init__(self, k):
        self.k = k
        self.time = 0.0
        self.events = []
        self.nodes = {}  # id -> Node
        self.event_counter = 0

    def schedule(self, delay, func, *args):
        self.event_counter += 1
        heapq.heappush(self.events, (self.time + delay, self.event_counter, func, args))

    def run(self):
        while self.events:
            t, _, func, args = heapq.heappop(self.events)
            self.time = t
            func(*args)

    def is_successful(self):
        if not self.nodes:
            return True
        
        # Check connected components
        visited = set()
        start_node = next(iter(self.nodes.keys()))
        
        queue = [start_node]
        visited.add(start_node)
        while queue:
            curr = queue.pop(0)
            for nbr in self.nodes[curr].neighbors:
                if nbr not in visited:
                    visited.add(nbr)
                    queue.append(nbr)
                    
        # If not fully connected
        if len(visited) != len(self.nodes):
            return False
            
        # Check max degree constraint
        for node in self.nodes.values():
            if len(node.neighbors) > self.k:
                return False
                
        # Check symmetric edges
        for node_id, node in self.nodes.items():
            for nbr in node.neighbors:
                if node_id not in self.nodes[nbr].neighbors:
                    return False

        return True

class Node:
    def __init__(self, node_id, sim):
        self.id = node_id
        self.sim = sim
        self.neighbors = set()
        self.state = "OFFLINE"
        self.collected_acks = {}
        self.discovery_timeout = 2000  # ms
        self.delay_min = 10
        self.delay_max = 50

    def get_delay(self):
        return random.uniform(self.delay_min, self.delay_max)

    def start(self):
        self.state = "JOINING"
        self.collected_acks = {}
        # Send DISCOVER
        for other in self.sim.nodes.values():
            if other.id != self.id and other.state != "OFFLINE":
                self.sim.schedule(self.get_delay(), other.receive_discover, self.id)
                
        self.sim.schedule(self.discovery_timeout, self.process_plan)

    def receive_discover(self, sender_id):
        self.sim.schedule(self.get_delay(), self.sim.nodes[sender_id].receive_ack, self.id, set(self.neighbors))

    def receive_ack(self, sender_id, sender_neighbors):
        if self.state == "JOINING":
            self.collected_acks[sender_id] = sender_neighbors

    def process_plan(self):
        plan = self.create_plan()
        for target in plan['direct_targets']:
            victim = plan['evictions'].get(target)
            if victim is None:
                self.sim.schedule(self.get_delay(), self.sim.nodes[target].receive_commit_small, self.id)
            else:
                self.sim.schedule(self.get_delay(), self.sim.nodes[target].receive_commit_direct, self.id, victim)
        
        self.state = "IN_NETWORK"

    def receive_commit_small(self, sender_id):
        # target checks if space
        if len(self.neighbors) < self.sim.k:
            self.neighbors.add(sender_id)
            self.sim.schedule(self.get_delay(), self.sim.nodes[sender_id].receive_commit_small_ack, self.id, True)
        else:
            self.sim.schedule(self.get_delay(), self.sim.nodes[sender_id].receive_commit_small_ack, self.id, False)

    def receive_commit_small_ack(self, sender_id, success):
        if success and len(self.neighbors) < self.sim.k:
            self.neighbors.add(sender_id)

    def receive_commit_direct(self, sender_id, victim_id):
        # DirectTarget receives this
        if victim_id in self.neighbors:
            # Send COMMIT_DELETE to victim
            self.sim.schedule(self.get_delay(), self.sim.nodes[victim_id].receive_commit_delete, self.id, sender_id)
        else:
            self.sim.schedule(self.get_delay(), self.sim.nodes[sender_id].receive_commit_direct_ack, self.id, False)

    def receive_commit_delete(self, direct_id, sender_id):
        # Victim receives this
        if direct_id in self.neighbors:
            self.neighbors.remove(direct_id)
        self.sim.schedule(self.get_delay(), self.sim.nodes[direct_id].receive_commit_delete_ack, self.id, sender_id)

    def receive_commit_delete_ack(self, victim_id, sender_id):
        # DirectTarget receives ACK_DELETE
        if victim_id in self.neighbors:
            self.neighbors.remove(victim_id)
        if len(self.neighbors) < self.sim.k:
            self.neighbors.add(sender_id)
            self.sim.schedule(self.get_delay(), self.sim.nodes[sender_id].receive_commit_direct_ack, self.id, True)
        else:
            self.sim.schedule(self.get_delay(), self.sim.nodes[sender_id].receive_commit_direct_ack, self.id, False)

    def receive_commit_direct_ack(self, direct_id, success):
        # JoiningNode receives ACK_DIRECT
        if success:
            if len(self.neighbors) < self.sim.k:
                self.neighbors.add(direct_id)
            victim_id = self.original_plan['evictions'].get(direct_id)
            if victim_id:
                self.sim.schedule(self.get_delay(), self.sim.nodes[victim_id].receive_commit_victim, self.id, direct_id)

    def receive_commit_victim(self, sender_id, old_direct_id):
        if len(self.neighbors) < self.sim.k:
            self.neighbors.add(sender_id)
            self.sim.schedule(self.get_delay(), self.sim.nodes[sender_id].receive_commit_victim_ack, self.id, True)
        else:
            self.sim.schedule(self.get_delay(), self.sim.nodes[sender_id].receive_commit_victim_ack, self.id, False)

    def receive_commit_victim_ack(self, victim_id, success):
        if success and len(self.neighbors) < self.sim.k:
            self.neighbors.add(victim_id)

    def create_plan(self):
        k = self.sim.k
        unique_acks = list(self.collected_acks.items())
        random.shuffle(unique_acks)
        
        sufficient_nodes = [(nid, nbrs) for nid, nbrs in unique_acks if len(nbrs) >= k]
        deficient_nodes = [(nid, nbrs) for nid, nbrs in unique_acks if len(nbrs) < k]
        
        if len(deficient_nodes) + len(sufficient_nodes) * 2 <= k:
            def_targets = len(deficient_nodes)
            suff_targets = len(sufficient_nodes)
        else:
            even_def = len(deficient_nodes)
            if even_def % 2 == 1:
                even_def -= 1
            even_def = min(even_def, k)
            suff_targets = min((k - even_def) // 2, len(sufficient_nodes))
            min_suff = 0
            if suff_targets < min_suff and k >= 2 * min_suff:
                suff_targets = min_suff
            def_targets = k - 2 * suff_targets
            
        chosen_suff = sufficient_nodes[:suff_targets]
        chosen_def = deficient_nodes[:def_targets]
        
        direct_targets = [nid for nid, _ in chosen_def] + [nid for nid, _ in chosen_suff]
        
        evictions = {}
        victim_ids = set([self.id] + direct_targets)
        for nid, nbrs in chosen_suff:
            candidates = list(nbrs)
            random.shuffle(candidates)
            for c in candidates:
                if c not in victim_ids:
                    evictions[nid] = c
                    victim_ids.add(c)
                    break
                    
        self.original_plan = {'direct_targets': direct_targets, 'evictions': evictions}
        return self.original_plan
