#!/usr/bin/env python3
"""
Convenience runner for the Alice Agent E2E test suite.

Usage:
    # Build then test (takes longer)
    python run_alice_e2e.py --build

    # Test only (assumes already built)
    python run_alice_e2e.py

    # Run a specific test class
    python run_alice_e2e.py TestAliceCliHelp

    # Run with verbose output
    python run_alice_e2e.py -v

Environment variables:
    GEMMA4_BASE_URL  (default: http://192.168.1.14:10303/v1)
    GEMMA4_MODEL     (default: gemma-4)
    GEMMA4_TIMEOUT   (default: 180)
"""

import sys
import unittest

if __name__ == "__main__":
    import os

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

    # Default verbosity
    if "-v" not in sys.argv:
        sys.argv.append("-v")

    # Discover and run tests
    loader = unittest.TestLoader()
    suite = loader.discover(
        os.path.dirname(os.path.abspath(__file__)),
        pattern="alice_agent_e2e*.py",
    )

    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)

    print()
    print("=" * 64)
    print(f"  Tests run: {result.testsRun}")
    print(f"  Passed:    {result.testsRun - len(result.failures) - len(result.errors)}")
    print(f"  Failures:  {len(result.failures)}")
    print(f"  Errors:    {len(result.errors)}")
    print(f"  Skipped:   {len(result.skipped)}")
    print("=" * 64)

    sys.exit(0 if result.wasSuccessful() else 1)
