import time
import os

import networkx as nx
import matplotlib.pyplot as plt

GRAPH_FILE = "graph.txt"

def parse_graph():
    nodes = []
    node_set = set()
    edges = []

    with open(GRAPH_FILE, "r") as f:
        for line in f:
            line = line.strip()

            if not line:
                continue

            parts = line.split()

            if len(parts) == 1:
                node = parts[0]
                if node not in node_set:
                    nodes.append(node)
                    node_set.add(node)

            elif len(parts) == 2:
                u, v = parts
                if u not in node_set:
                    nodes.append(u)
                    node_set.add(u)
                if v not in node_set:
                    nodes.append(v)
                    node_set.add(v)
                edges.append((u, v))

    nodes.sort()
    return nodes, edges


def build_graph():
    nodes, edges = parse_graph()
    G = nx.DiGraph()
    G.add_nodes_from(nodes)
    G.add_edges_from(edges)
    return G


plt.ion()  # Interactive mode ON
fig = plt.figure()
plt.show(block=False)
last_modified = 0

while True:
    try:
        current_modified = os.path.getmtime(GRAPH_FILE)
        if current_modified != last_modified:
            last_modified = current_modified
            nodes, edges = parse_graph()
            G = nx.DiGraph()
            G.add_nodes_from(nodes)
            G.add_edges_from(edges)
            plt.clf()
            pos = nx.circular_layout(nodes)
            nx.draw(G, pos, with_labels=True, arrows=True, node_size=2000, font_size=12)
            plt.title("Live Graph Viewer")
            plt.draw()
            plt.pause(0.1)
            print("Graph updated")
        plt.pause(1)

    except KeyboardInterrupt:
        break