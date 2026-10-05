#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Install VoxelProbe's local Python tools and optionally register Codex.

Run: python tool/install.py --game-dir /path/to/minecraft-instance
Add --client codex to register MCP, or --install-skills to copy the bundled skills.
Only this package's tool/.venv receives Python dependencies.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


SKILL_NAMES = ("voxelprobe-inspect", "voxelprobe-build")


def environment_python(tool_dir: Path) -> Path:
    """Return this package's interpreter path without depending on the working directory."""
    return tool_dir / ".venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


def mcp_template(tool_dir: Path, game_dir: Path) -> dict:
    """Generic stdio MCP configuration; connection secrets stay in the game instance."""
    return {"mcpServers": {"voxelprobe": {
        "command": str(environment_python(tool_dir)),
        "args": [str(tool_dir / "mc.py"), "mcp"],
        "env": {"MINECRAFT_GAME_DIR": str(game_dir)},
    }}}


def codex_command(executable: str, tool_dir: Path, game_dir: Path) -> list[str]:
    return [executable, "mcp", "add", "voxelprobe", "--env",
            f"MINECRAFT_GAME_DIR={game_dir}", "--",
            str(environment_python(tool_dir)), str(tool_dir / "mc.py"), "mcp"]


def install_dependencies(tool_dir: Path) -> None:
    requirements = tool_dir / "requirements.lock"
    if not requirements.is_file() or not (tool_dir / "mc.py").is_file():
        raise ValueError("Incomplete VoxelProbe package: tool/mc.py and requirements.lock are required.")
    python = environment_python(tool_dir)
    if not python.is_file():
        subprocess.run([sys.executable, "-m", "venv", str(tool_dir / ".venv")], check=True)
    subprocess.run([str(python), "-m", "pip", "install", "-r", str(requirements)], check=True)


def install_skills(tool_dir: Path, codex_home: Path) -> list[str]:
    """Copy bundled instructions; leave every existing same-name directory untouched."""
    messages = []
    for name in SKILL_NAMES:
        source = tool_dir / "skills" / name / "SKILL.md"
        if not source.is_file():
            raise ValueError(f"Incomplete VoxelProbe package: missing {source}")
    destination = codex_home / "skills"
    for name in SKILL_NAMES:
        target = destination / name
        if target.exists() or target.is_symlink():
            messages.append(f"Preserved existing skill: {target}")
            continue
        target.mkdir(parents=True)
        try:
            with (target / "SKILL.md").open("xb") as output:
                output.write((tool_dir / "skills" / name / "SKILL.md").read_bytes())
        except Exception:
            # The directory was just created here; retain any concurrently added files.
            if not any(target.iterdir()):
                target.rmdir()
            raise
        messages.append(f"Installed skill: {target}")
    return messages


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game-dir", type=Path, required=True,
                        help="Existing Minecraft instance directory, containing its mods/config directories")
    parser.add_argument("--client", choices=("codex",),
                        help="Explicitly register the voxelprobe stdio MCP server with Codex")
    parser.add_argument("--install-skills", action="store_true",
                        help="Copy bundled skills to CODEX_HOME/skills (existing skills are preserved)")
    args = parser.parse_args(argv)
    if sys.version_info < (3, 11):
        parser.error("VoxelProbe tools require Python 3.11 or newer.")
    game_dir = args.game_dir.expanduser().resolve()
    if not game_dir.is_dir():
        parser.error(f"Minecraft instance directory does not exist: {game_dir}")
    tool_dir = Path(__file__).resolve().parent
    codex = shutil.which("codex") if args.client == "codex" else None
    if args.client and not codex:
        parser.error("Codex CLI is not on PATH. Install it or omit --client and use the printed MCP template.")
    try:
        install_dependencies(tool_dir)
        print(f"Python tools installed in {tool_dir / '.venv'}")
        print("Generic MCP configuration (for clients supporting stdio):")
        print(json.dumps(mcp_template(tool_dir, game_dir), ensure_ascii=False, indent=2))
        if codex:
            subprocess.run(codex_command(codex, tool_dir, game_dir), check=True)
            print("Registered Codex MCP server: voxelprobe. Open a new chat to load its tools.")
        if args.install_skills:
            codex_home = Path(os.environ.get("CODEX_HOME") or Path.home() / ".codex").expanduser().resolve()
            for message in install_skills(tool_dir, codex_home):
                print(message)
        connection = game_dir / "config" / "voxel_probe_connection.json"
        legacy_connection = game_dir / "config" / "debugbridge-connection.json"
        if connection.is_file() or legacy_connection.is_file():
            print("Connection descriptor exists; launch this instance and call minecraft_read(status) to verify it.")
        else:
            print("Not connected yet: start this Minecraft instance with VoxelProbe installed, then enable the mod.")
            print(f"The mod will create {connection}; then call minecraft_read(status) to verify the intended world.")
        print("The private connection file contains the access token. Do not copy it into MCP configuration or share it.")
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as exc:
        print(f"VoxelProbe installation failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
