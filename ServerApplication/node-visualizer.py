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
            # nx.draw(G, pos, with_labels=True, arrows=True, node_size=3000, font_size=12, node_color="#98FF98", edgecolors="#B2B2B2", linewidths=2, width=2.5, arrowsize=25)
            NODE_SIZE = 4000

            # Draw nodes
            nx.draw_networkx_nodes(
                G,
                pos,
                node_size=NODE_SIZE,
                node_color="#98FF98",
                edgecolors="#B2B2B2",
                linewidths=2
            )

            # Draw edges
            nx.draw_networkx_edges(
                G,
                pos,
                width=2.5,
                arrows=True,
                arrowsize=25
            )

            # Draw labels with dynamic font sizes
            for node, (x, y) in pos.items():
                label = str(node)

                # Auto-scale font size based on label length
                font_size = max(6, min(26, int(26 - len(label) * 2)))

                plt.text(
                    x,
                    y,
                    label,
                    fontsize=font_size,
                    ha="center",
                    va="center",
                    color="black",
                    fontweight="bold"
                )
            plt.draw()
            plt.axis("off")
            plt.margins(0.2)
            plt.tight_layout()
            plt.pause(0.1)
            print("Graph updated")
        plt.pause(1)

    except KeyboardInterrupt:
        break