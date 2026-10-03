#!/usr/bin/env python3
"""
MCR Master Verification & Test Orchestrator
Modular test network runner with multi-suite selection and flag management.
"""
import sys
import os
import argparse
import time

sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))

from tests.common import log_header
from tests.test_matchmaking import run_all_matchmaking_tests
from tests.test_custom_kits import run_all_custom_kit_tests
from tests.test_daemon_bridge import run_all_daemon_tests
from tests.test_mod_integrity import run_all_mod_integrity_tests
from tests.test_versioning import run_all_versioning_tests

def parse_args():
    parser = argparse.ArgumentParser(
        description="Minecraft Ranks (MCR) Comprehensive Test Framework",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  ./test_all.py                      # Run entire test network
  ./test_all.py --quick              # Run smoke test suite (fast)
  ./test_all.py --matchmaking        # Test only matchmaking server & pairing
  ./test_all.py --kits               # Test custom kit architecture & schemas
  ./test_all.py --daemon             # Test Go daemon, JNA, & P2P tsnet tunnel
  ./test_all.py --mod                # Test Fabric mod mixins & Java compile
  ./test_all.py --stress             # Run heavy concurrency & queue stress test
"""
    )
    parser.add_argument("-a", "--all", action="store_true", help="Run all test suites (default if no specific suite selected)")
    parser.add_argument("-m", "--matchmaking", action="store_true", help="Run matchmaking server, queue pairing & ELO tests")
    parser.add_argument("-k", "--kits", action="store_true", help="Run custom kit data model, editor & schema tests")
    parser.add_argument("-d", "--daemon", "--p2p", dest="daemon", action="store_true", help="Run Go daemon, JNA alignment & tsnet P2P tests")
    parser.add_argument("-b", "--mod", action="store_true", help="Run Fabric mod metadata, mixin alignment & Java compile tests")
    parser.add_argument("-v", "--versioning", action="store_true", help="Run AutoUpdater versioning logic tests")
    parser.add_argument("-s", "--stress", action="store_true", help="Include multi-threaded queue concurrency stress testing")
    parser.add_argument("-q", "--quick", action="store_true", help="Quick mode: bypass heavy Gradle compile and tsnet live tunnel")

    return parser.parse_args()

def main():
    args = parse_args()

    # If no specific suite selected, default to running all suites
    run_all = args.all or not (args.matchmaking or args.kits or args.daemon or args.mod or args.versioning)

    start_time = time.time()
    print("\033[1;35m========================================================\033[0m")
    print("\033[1;35m    MCR DYNAMIC TEST & VERIFICATION FRAMEWORK           \033[0m")
    print("\033[1;35m========================================================\033[0m")

    suite_results = {}

    if run_all or args.versioning:
        suite_results["Versioning"] = run_all_versioning_tests()

    if run_all or args.kits:
        suite_results["Custom Kits"] = run_all_custom_kit_tests()

    if run_all or args.daemon:
        suite_results["Go Daemon & P2P"] = run_all_daemon_tests(skip_live=args.quick)

    if run_all or args.mod:
        suite_results["Fabric Mod Integrity"] = run_all_mod_integrity_tests(skip_compile=args.quick)

    if run_all or args.matchmaking:
        suite_results["Matchmaking Server"] = run_all_matchmaking_tests(stress=args.stress or run_all)

    elapsed = time.time() - start_time
    total_suites = len(suite_results)
    passed_suites = sum(1 for passed in suite_results.values() if passed)
    failed_suites = total_suites - passed_suites

    print("\n\033[1;35m========================================================\033[0m")
    print(f"\033[1;37mTEST SUMMARY: Completed in {elapsed:.2f}s\033[0m")
    for name, passed in suite_results.items():
        tag = "\033[92m[PASSED]\033[0m" if passed else "\033[91m[FAILED]\033[0m"
        print(f"  {tag} {name}")
    print("\033[1;35m========================================================\033[0m")

    if failed_suites == 0:
        print("\033[1;92m✔ ALL TEST SUITES PASSED CLEANLY! Ready for release.\033[0m\n")
        sys.exit(0)
    else:
        print(f"\033[1;91m✘ {failed_suites} of {total_suites} TEST SUITES FAILED!\033[0m\n")
        sys.exit(1)

if __name__ == "__main__":
    main()
