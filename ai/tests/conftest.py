import sys
from pathlib import Path

# The service modules live one folder up; tests run from anywhere.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
