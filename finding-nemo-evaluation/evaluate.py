import argparse
import csv
import os
import random
import time
from simulator import Simulation, Node

def run_bootstrap_test(k, trials):
    """
    Test 1 (Bootstrap): Inject k+1 nodes into an empty network. 
    Output: % success vs k, tracking the number of trials.
    """
    success_count = 0
    for _ in range(trials):
        sim = Simulation(k)
        # Create k+1 nodes
        for i in range(k + 1):
            node_id = f"node_{i}"
            sim.nodes[node_id] = Node(node_id, sim)
            
        # Start all nodes simultaneously
        for node in sim.nodes.values():
            node.start()
            
        sim.run()
        if sim.is_successful():
            success_count += 1
            
    return (success_count / trials) * 100 if trials > 0 else 0

def run_sequential_churn_test(k, g, trials):
    """
    Test 2 (Sequential Churn): Inject g nodes sequentially. 
    Output: % success vs g line chart. % Success vs k line chart. 
    """
    success_count = 0
    for _ in range(trials):
        sim = Simulation(k)
        
        # Start with an established network of k+1 nodes
        for i in range(k + 1):
            node_id = f"init_{i}"
            sim.nodes[node_id] = Node(node_id, sim)
            sim.nodes[node_id].start()
        sim.run() # Form initial network
        
        # Sequentially inject g nodes
        for i in range(g):
            node_id = f"churn_{i}"
            sim.nodes[node_id] = Node(node_id, sim)
            sim.nodes[node_id].start()
            sim.run() # Wait for each node to join
            
        if sim.is_successful():
            success_count += 1
            
    return (success_count / trials) * 100 if trials > 0 else 0

def run_concurrent_burst_test(k, s, trials):
    """
    Test 3 (Concurrent Burst): Inject s nodes simultaneously. 
    """
    success_count = 0
    for _ in range(trials):
        sim = Simulation(k)
        
        # Start with an established network of k+1 nodes
        for i in range(k + 1):
            node_id = f"init_{i}"
            sim.nodes[node_id] = Node(node_id, sim)
            sim.nodes[node_id].start()
        sim.run() # Form initial network
        
        # Concurrently inject s nodes
        for i in range(s):
            node_id = f"burst_{i}"
            sim.nodes[node_id] = Node(node_id, sim)
            sim.nodes[node_id].start()
            
        sim.run() # Run all concurrent joins
            
        if sim.is_successful():
            success_count += 1
            
    return (success_count / trials) * 100 if trials > 0 else 0

def run_large_overlay_burst_test(k, s, trials, initial_nodes=10_000_000):
    """
    Test 4 (Concurrent Burst in Large Overlay): Inject s nodes simultaneously
    into an established large overlay of initial_nodes (e.g. 10M nodes).
    """
    success_count = 0
    for _ in range(trials):
        sim = Simulation(k, total_nodes=initial_nodes)
        
        def get_or_create_overlay_node(nid):
            if nid in sim.nodes:
                return sim.nodes[nid]
            node = Node(nid, sim)
            node.state = "IN_NETWORK"
            # Assign k symbolic neighbors from the 10M network
            node.neighbors = set(f"ext_{nid}_{i}" for i in range(k))
            sim.nodes[nid] = node
            return node

        burst_nodes = []
        for i in range(s):
            bnode = Node(f"burst_{i}", sim)
            bnode.state = "JOINING"
            sim.nodes[bnode.id] = bnode
            burst_nodes.append(bnode)

        for bnode in burst_nodes:
            targets = [f"init_{tid}" for tid in random.sample(range(initial_nodes), k // 2)]
            evictions = {}
            for tid in targets:
                tnode = get_or_create_overlay_node(tid)
                candidates = [c for c in tnode.neighbors if c not in evictions.values()]
                victim_id = random.choice(candidates if candidates else list(tnode.neighbors))
                evictions[tid] = victim_id
                if victim_id not in sim.nodes:
                    vnode = Node(victim_id, sim)
                    vnode.state = "IN_NETWORK"
                    vnode.neighbors = set([tid] + [f"ext_{victim_id}_{j}" for j in range(k - 1)])
                    sim.nodes[victim_id] = vnode
            
            bnode.original_plan = {'direct_targets': targets, 'evictions': evictions}
            for target in targets:
                victim = evictions[target]
                sim.schedule(bnode.get_delay(), sim.nodes[target].receive_commit_direct, bnode.id, victim)

        sim.run()
        if sim.is_successful():
            success_count += 1
            
    return (success_count / trials) * 100 if trials > 0 else 0

def append_to_csv(filename, row_dict):
    file_exists = os.path.isfile(filename)
    with open(filename, 'a', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=row_dict.keys())
        if not file_exists:
            writer.writeheader()
        writer.writerow(row_dict)

def main():
    parser = argparse.ArgumentParser(description="Evaluate FINDING NEMO protocol")
    parser.add_argument('--test', type=int, required=True, choices=[1, 2, 3, 4], help="Test number")
    parser.add_argument('--k', type=int, help="Target degree limit", default=4)
    parser.add_argument('--g', type=int, help="Sequential additions (Test 2)", default=10)
    parser.add_argument('--s', type=int, help="Concurrent burst size (Test 3 & 4)", default=5)
    parser.add_argument('--initial-nodes', type=int, help="Initial nodes in overlay (Test 4)", default=10_000_000)
    parser.add_argument('--trials', type=int, default=10, help="Number of trials")
    parser.add_argument('--member-name', type=str, default="results", help="Prefix for CSV")
    
    args = parser.parse_args()
    
    filename = f"{args.member_name}_test{args.test}.csv"
    
    if args.test == 1:
        print(f"Running Test 1 (Bootstrap) with k={args.k}, trials={args.trials}")
        pct_success = run_bootstrap_test(args.k, args.trials)
        append_to_csv(filename, {
            'k': args.k,
            'trials': args.trials,
            'success_rate': pct_success,
            'timestamp': time.time()
        })
        print(f"Result: {pct_success}% success appended to {filename}")
        
    elif args.test == 2:
        print(f"Running Test 2 (Sequential Churn) with k={args.k}, g={args.g}, trials={args.trials}")
        pct_success = run_sequential_churn_test(args.k, args.g, args.trials)
        append_to_csv(filename, {
            'k': args.k,
            'g': args.g,
            'trials': args.trials,
            'success_rate': pct_success,
            'timestamp': time.time()
        })
        print(f"Result: {pct_success}% success appended to {filename}")
        
    elif args.test == 3:
        print(f"Running Test 3 (Concurrent Burst) with k={args.k}, s={args.s}, trials={args.trials}")
        pct_success = run_concurrent_burst_test(args.k, args.s, args.trials)
        append_to_csv(filename, {
            'k': args.k,
            's': args.s,
            'trials': args.trials,
            'success_rate': pct_success,
            'timestamp': time.time()
        })
        print(f"Result: {pct_success}% success appended to {filename}")

    elif args.test == 4:
        print(f"Running Test 4 (Large Overlay Burst) with k={args.k}, s={args.s}, N={args.initial_nodes}, trials={args.trials}")
        pct_success = run_large_overlay_burst_test(args.k, args.s, args.trials, initial_nodes=args.initial_nodes)
        append_to_csv(filename, {
            'k': args.k,
            's': args.s,
            'trials': args.trials,
            'success_rate': pct_success,
            'timestamp': time.time()
        })
        print(f"Result: {pct_success}% success appended to {filename}")

if __name__ == "__main__":
    main()

