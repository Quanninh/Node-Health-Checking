"""
Unit tests for the FINDING NEMO Evaluation framework.
Verifies topology analysis, convergence conditions, simulation logic, and plotter.
"""

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from config import EvaluationConfig
from nemo_simulator import NemoSimulator
from plotter import NemoPlotter, render_svg_line_chart
from topology_analyzer import TopologyAnalyzer


class TestTopologyAnalyzer(unittest.TestCase):
    def test_clique_convergence_small_network(self) -> None:
        # 5 nodes with k=4 forms a complete graph K_5 where every node has degree 4
        nodes = ["n0", "n1", "n2", "n3", "n4"]
        neighbor_map = {u: [v for v in nodes if v != u] for u in nodes}
        analysis = TopologyAnalyzer.analyze(neighbor_map, set(nodes), target_degree=4)
        self.assertTrue(analysis.is_converged)
        self.assertEqual(len(analysis.problem_owners), 0)

    def test_deficient_node_detected(self) -> None:
        nodes = ["n0", "n1", "n2", "n3", "n4"]
        neighbor_map = {u: [v for v in nodes if v != u] for u in nodes}
        # Drop one edge
        neighbor_map["n0"].remove("n1")
        neighbor_map["n1"].remove("n0")
        analysis = TopologyAnalyzer.analyze(neighbor_map, set(nodes), target_degree=4)
        self.assertFalse(analysis.is_converged)
        self.assertIn("n0", analysis.deficient_nodes)
        self.assertIn("n1", analysis.deficient_nodes)

    def test_non_mutual_edge_detected(self) -> None:
        nodes = ["n0", "n1", "n2", "n3", "n4"]
        neighbor_map = {u: [v for v in nodes if v != u] for u in nodes}
        # n0 thinks n1 is a neighbor, but n1 doesn't have n0
        neighbor_map["n1"].remove("n0")
        analysis = TopologyAnalyzer.analyze(neighbor_map, set(nodes), target_degree=4)
        self.assertFalse(analysis.is_converged)
        self.assertTrue(len(analysis.non_mutual_nodes) > 0)

    def test_self_edge_detected(self) -> None:
        nodes = ["n0", "n1"]
        neighbor_map = {"n0": ["n0", "n1"], "n1": ["n0"]}
        analysis = TopologyAnalyzer.analyze(neighbor_map, set(nodes), target_degree=2)
        self.assertFalse(analysis.is_converged)
        self.assertIn("n0", analysis.self_edge_nodes)

    def test_stale_edge_detected(self) -> None:
        nodes = ["n0", "n1"]
        neighbor_map = {"n0": ["n1", "dead_node"], "n1": ["n0"]}
        analysis = TopologyAnalyzer.analyze(neighbor_map, set(nodes), target_degree=2)
        self.assertFalse(analysis.is_converged)
        self.assertIn("n0", analysis.stale_edge_nodes)


class TestNemoSimulator(unittest.TestCase):
    def setUp(self) -> None:
        self.config = EvaluationConfig(k=4, g=6, trials=3)
        self.simulator = NemoSimulator(self.config)

    def test_test1_small_network_converges(self) -> None:
        converged, analysis = self.simulator.run_test1_trial(k=4)
        self.assertTrue(converged)
        self.assertEqual(analysis.actual_count, 5)

    def test_test2_scaled_network_converges(self) -> None:
        converged, analysis = self.simulator.run_test2_trial(k=4, g=6)
        self.assertTrue(converged)
        self.assertEqual(analysis.actual_count, 11)  # 5 base + 6 joined

    def test_test3_burst_churn_runs(self) -> None:
        converged, analysis = self.simulator.run_test3_trial(k=4, burst_size_s=4)
        # Should run without crashing and produce a valid topology analysis
        self.assertIsInstance(converged, bool)
        self.assertIsInstance(analysis.problem_owners, list)


class TestPlotter(unittest.TestCase):
    def test_svg_generation(self) -> None:
        with TemporaryDirectory() as tmp_dir:
            p = Path(tmp_dir) / "test.svg"
            render_svg_line_chart([2, 4, 6], [100.0, 95.0, 90.0], "Test", "X", "Y", p)
            self.assertTrue(p.exists())
            content = p.read_text(encoding="utf-8")
            self.assertIn("<svg", content)
            self.assertIn("Test", content)


if __name__ == "__main__":
    unittest.main()

