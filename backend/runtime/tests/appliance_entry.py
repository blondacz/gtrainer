"""Test-only static command injection. Excluded from all production packaging."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from pid1_supervisor import Appliance, configuration

config = configuration(sys.argv[1])
fixture = str(Path(__file__).with_name("fixture_process.py"))
Appliance(config, [sys.executable, fixture, "detached", sys.argv[2]]).run()
