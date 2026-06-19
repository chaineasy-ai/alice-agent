#!/usr/bin/env python3
"""
Convenience runner for the Gemma4 E2E test suite.

Usage:
    # Install dependencies first
    pip install -r requirements.txt

    # Run all tests
    python run_e2e.py

    # Run with verbose output
    python run_e2e.py -v

    # Run a specific test
    python run_e2e.py TestGemma4E2E.test_basic_chat

    # Override API endpoint via environment variables
    set GEMMA4_BASE_URL=http://192.168.1.14:10303/v1
    set GEMMA4_MODEL=gemma-4
    python run_e2e.py
"""

import sys
import unittest

if __name__ == "__main__":
    # Ensure we can import the test module
    import os

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

    # Default verbosity
    verbosity = 2 if "-v" in sys.argv else 2

    # Discover and run tests
    loader = unittest.TestLoader()
    suite = loader.discover(
        os.path.dirname(os.path.abspath(__file__)),
        pattern="*_e2e_test.py",
    )

    runner = unittest.TextTestRunner(verbosity=verbosity)
    result = runner.run(suite)

    # Exit with proper code
    sys.exit(0 if result.wasSuccessful() else 1)
