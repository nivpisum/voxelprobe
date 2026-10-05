"""One local entry point: Minecraft RPC, schematic files, and MCP/CLI."""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import uuid
import threading
from pathlib import Path
from typing import Any

from mcp.server.fastmcp import FastMCP, Image
from mcp.types import CallToolResult, TextContent
from websockets.sync.client import connect
# Initialize NumPy/NBT before the Windows stdio reader threads start; importing
# its native DLLs for the first time inside a tool request can deadlock.
from building import export_build, read_build

DEFAULT_PORT = int(os.environ.get("MC_BRIDGE_PORT", "9876"))
READ_OPS = {"status", "block", "region", "entities", "observe_result", "preview"}
WRITE_OPS = {"batch", "command", "save", "structure_save", "structure_load", "observe_start", "observe_stop"}
CLIENT_READS = {"snapshot", "screenInspect", "search", "nearbyEntities", "entityDetails", "nearbyBlocks", "blockDetails", "chatHistory"}
_bindings: dict[int, dict] = {}
_binding_lock = threading.RLock()


class BridgeError(RuntimeError):
    def __init__(self, response: dict):
        super().__init__(response.get("error", "Minecraft request failed"))
        self.result = response.get("result")
        self.output = response.get("output")

    def details(self) -> dict:
        result = {"error": str(self)}
        if self.result is not None: result["result"] = self.result
        if self.output: result["output"] = self.output
        return result

    def tool_result(self) -> CallToolResult:
        body = self.details()
        return CallToolResult(isError=True, content=[TextContent(type="text", text=json.dumps(body, ensure_ascii=False))], structuredContent=body)


def connection(port: int) -> dict:
    game = os.environ.get("MINECRAFT_GAME_DIR")
    if not game:
        # Retains the existing explicitly local, private installation's CLI compatibility.
        return {"port": port, "headers": {}, "game_dir": None, "instance_id": None}
    directory = Path(game).expanduser().resolve()
    path = directory / "config/voxel_probe_connection.json"
    if not path.exists():
        # Support an instance that has not restarted after the ID migration.
        path = directory / "config/debugbridge-connection.json"
    if not path.is_file():
        raise RuntimeError("Start this instance with VoxelProbe and enable inspection; its private connection file is not present yet.")
    try:
        info = json.loads(path.read_text(encoding="utf-8-sig"))
        actual = int(info["port"]) if port == DEFAULT_PORT else port
        if info["protocol_version"] != 2 or not 1 <= actual <= 65535 or not isinstance(info["token"], str):
            raise ValueError()
        if Path(info["game_dir"]).resolve() != directory: raise ValueError()
        return {"port": actual, "headers": {"Authorization": "Bearer " + info["token"]},
                "game_dir": directory, "instance_id": info["instance_id"]}
    except (ValueError, KeyError, TypeError):
        raise RuntimeError("Invalid VoxelProbe connection file; launch the intended instance again.") from None


def _request(kind: str, payload: dict, info: dict, timeout: float = 45) -> Any:
    request_id = str(uuid.uuid4())
    try:
        with connect(f"ws://127.0.0.1:{info['port']}", additional_headers=info["headers"], open_timeout=5,
                     close_timeout=2, max_size=32 * 1024 * 1024) as ws:
            ws.send(json.dumps({"id": request_id, "type": kind, "payload": payload}, ensure_ascii=False))
            response = json.loads(ws.recv(timeout=timeout))
    except OSError as exc:
        raise RuntimeError(f"Minecraft bridge unavailable on local port {info['port']}; launch the configured instance.") from exc
    if response.get("id") != request_id: raise RuntimeError("Mismatched Minecraft response id")
    if not response.get("success"): raise BridgeError(response)
    result = response.get("result")
    return {"result": result, "output": response["output"]} if response.get("output") else result


def rpc(kind: str, payload: dict | None = None, port: int = DEFAULT_PORT, timeout: float = 45) -> Any:
    info = connection(port)
    arguments = dict(payload or {})
    status = None
    if info["game_dir"] is not None:
        status = _request("status", {}, info)
        if Path(status.get("gameDir", "")).resolve() != info["game_dir"] or status.get("instance_id") != info["instance_id"]:
            raise RuntimeError("TARGET_CHANGED: this port does not belong to the configured instance")
        if kind == "status": return status
    op = arguments.get("op")
    mutation = (kind == "world" and op in (WRITE_OPS | {"script"}) - {"observe_start", "observe_stop"}) or kind in {"execute", "runCommand", "quit", "disconnect", "joinServer"}
    if mutation:
        with _binding_lock:
            binding = _bindings.get(info["port"])
            if binding is None:
                binding = {"instance_id": (status or _request("status", {}, info)).get("instance_id")}
                try: binding["world_id"] = _request("world", {"op": "status"}, info).get("world_id")
                except BridgeError: binding["world_id"] = None
                _bindings[info["port"]] = binding
            if binding.get("instance_id") and info.get("instance_id") and binding["instance_id"] != info["instance_id"]:
                raise RuntimeError("TARGET_CHANGED: read status after the game restarted")
            if binding.get("instance_id"): arguments.setdefault("expected_instance_id", binding["instance_id"])
            if binding.get("world_id"): arguments.setdefault("expected_world_id", binding["world_id"])
    return _request(kind, arguments, info, timeout)


def world(op: str, arguments: dict | None = None, port: int = DEFAULT_PORT) -> Any:
    return rpc("world", {**(arguments or {}), "op": op}, port)


def read(operation: str = "status", arguments: dict | None = None, port: int = DEFAULT_PORT) -> Any:
    if operation == "status":
        status = {"bridge": rpc("status", port=port)}
        try:
            status["world"] = world("status", port=port)
        except RuntimeError as exc:
            status["world_unavailable"] = str(exc)
        info = connection(port)
        with _binding_lock:
            _bindings[info["port"]] = {"instance_id": status["bridge"].get("instance_id"), "world_id": status.get("world", {}).get("world_id")}
        return status
    if operation in READ_OPS:
        return world(operation, arguments, port)
    if operation in CLIENT_READS:
        return rpc(operation, arguments, port)
    raise ValueError(f"Unknown read operation. World: {sorted(READ_OPS)}; client: {sorted(CLIENT_READS)}")


def write(operation: str, arguments: dict | None = None, port: int = DEFAULT_PORT) -> Any:
    if operation not in WRITE_OPS:
        raise ValueError(f"Unknown write operation: choose {sorted(WRITE_OPS)}")
    return world(operation, arguments, port)


def script(code: str, side: str = "server", port: int = DEFAULT_PORT, timeout_ms: int = 10000) -> Any:
    if side not in {"client", "server"}:
        raise ValueError("side must be client or server")
    if not 1 <= timeout_ms <= 30000:
        raise ValueError("timeout_ms must be 1..30000")
    args = {"code": code, "timeoutMs": timeout_ms}
    result = world("script", args, port) if side == "server" else rpc("execute", args, port)
    return plain(result)


def plain(value: Any) -> Any:
    """Unwrap scalar/table values; retain object references and authority metadata."""
    if isinstance(value, list):
        return [plain(v) for v in value]
    if isinstance(value, dict):
        if value.get("type") in {"string", "number", "boolean", "table", "array", "null"} and "value" in value:
            return plain(value["value"])
        return {k: plain(v) for k,v in value.items()}
    return value


def view(output: str | None = None, port: int = DEFAULT_PORT, downscale: int = 1) -> dict:
    captured = rpc("screenshot", {"downscale": downscale, "quality": 0.9}, port)
    source = Path(captured["path"])
    if output:
        target = Path(output).resolve()
        target.parent.mkdir(parents=True, exist_ok=True)
        if source.resolve() != target:
            shutil.copy2(source, target)
        captured["path"] = str(target)
    return captured


def export(spec_path: str, output_dir: str, formats: list[str]) -> dict:
    return export_build(json.loads(Path(spec_path).read_text(encoding="utf-8-sig")), Path(output_dir), formats)


def place(path: str, origin: list[int], dimension: str = "minecraft:overworld", include_air: bool = False,
          port: int = DEFAULT_PORT) -> dict:
    spec = read_build(Path(path))
    if len(origin) != 3 or any(type(n) is not int for n in origin):
        raise ValueError("origin must be three integer coordinates")
    sources = spec["blocks"]
    if include_air:
        bounds = spec["bounds"]
        minimum, size = bounds["min"], bounds["size"]
        if size[0] * size[1] * size[2] > 65536:
            raise ValueError("Air-inclusive placement exceeds 65,536 cells; split the build or use WorldEdit")
        occupied = {(b["x"], b["y"], b["z"]): b for b in sources}
        sources = [occupied.get((x,y,z), {"x":x,"y":y,"z":z,"block":"minecraft:air"})
                   for y in range(minimum[1], minimum[1]+size[1])
                   for z in range(minimum[2], minimum[2]+size[2])
                   for x in range(minimum[0], minimum[0]+size[0])]
    blocks = []
    for source in sources:
        block = source["block"]
        if not include_air and block.split("[", 1)[0] in {"air", "minecraft:air", "minecraft:cave_air", "minecraft:void_air"}:
            continue
        row = dict(source)
        for index, axis in enumerate(("x", "y", "z")):
            row[axis] += origin[index]
        blocks.append(row)
    if len(blocks) > 65536:
        raise ValueError("Direct placement is capped at 65,536 blocks per call; split the build or import its .schem with WorldEdit")
    if spec.get("entities"):
        raise ValueError("Direct block placement does not place entities; use native structure/WorldEdit import")
    native_blocks = [[b["x"], b["y"], b["z"], b["block"]] + ([b["nbt"]] if "nbt" in b else []) for b in blocks]
    result = world("batch", {"dimension": dimension, "blocks": native_blocks}, port)
    return {"source": str(Path(path).resolve()), "origin": origin, "submitted": len(blocks), "result": result,
            "warnings": spec.get("warnings", [])}


mcp = FastMCP("VoxelProbe", instructions=(
    "VoxelProbe for Forge 1.20.1. Pair with MINECRAFT_GAME_DIR or --game-dir. "
    "Read minecraft_read(status) first and confirm gameDir and world_name before writing. "
    "World operations use the integrated server; client observations are not authoritative server state. "
    "Inspection is the default; editing and full-trust scripts require an in-game permission change. "
    "Use preview before bounded bulk edits, then read back. Scripts default to server and bind server, world/level, player; "
    "client scripts bind mc/player/level. Building files target DataVersion 3465. "
    "The CLI in this project exposes the same functions for tasks whose MCP catalog has not refreshed."
))


@mcp.tool(annotations={"readOnlyHint": True})
def minecraft_read(operation: str = "status", arguments: dict[str, Any] | None = None, port: int = DEFAULT_PORT) -> Any:
    """Inspect world status/block/region/entities/observe_result, or client snapshot/screenInspect/search.
    Block args: x,y,z,dimension. Region: from:[x,y,z],to:[x,y,z],limit. Native server response identifies its scope.
    """
    try: return read(operation, arguments, port)
    except BridgeError as exc: return exc.tool_result()


@mcp.tool(annotations={"destructiveHint": True})
def minecraft_write(operation: str, arguments: dict[str, Any], port: int = DEFAULT_PORT) -> Any:
    """Edit the authorized local world: batch/command/save/structure_save/structure_load/observe_start/observe_stop.
    batch: blocks:[[x,y,z,block,optional_SNBT]],dimension. command: command,player?:UUID.
    Read status first to confirm the intended instance; success of a call still requires behavior verification.
    """
    try: return write(operation, arguments, port)
    except BridgeError as exc: return exc.tool_result()


@mcp.tool(annotations={"destructiveHint": True})
def minecraft_script(code: str, side: str = "server", port: int = DEFAULT_PORT, timeout_ms: int = 10000) -> Any:
    """Run Groovy against live game objects using Mojang names. server side dispatches object access to the server thread.
    Bindings: server,world/level,player. sync { ... } batches on the selected side's thread. Return compact data.
    """
    try: return script(code, side, port, timeout_ms)
    except BridgeError as exc: return exc.tool_result()


@mcp.tool(annotations={"readOnlyHint": True})
def minecraft_view(port: int = DEFAULT_PORT, downscale: int = 1) -> Image:
    """See the actual rendered Minecraft framebuffer, including textures, lighting, GUI and mod rendering."""
    return Image(path=view(port=port, downscale=downscale)["path"])


@mcp.tool()
def minecraft_export(spec_path: str, output_dir: str, formats: list[str] = ["litematic", "schem", "nbt"]) -> dict:
    """Export a local JSON build to portable Minecraft 1.20.1 files. Blocks retain state and SNBT block-entity data.
    Spec example and exact schema are beside this script in example_build.json and building.py.
    """
    return export(spec_path, output_dir, formats)


@mcp.tool(annotations={"destructiveHint": True})
def minecraft_place(path: str, origin: list[int], dimension: str = "minecraft:overworld", include_air: bool = False,
                    port: int = DEFAULT_PORT) -> dict:
    """Place a .litematic/.schem/.nbt file through the server API, capped at 65,536 submitted blocks.
    origin offsets the file's logical coordinates. Air is skipped unless include_air=true. Read back to verify.
    """
    try: return place(path, origin, dimension, include_air, port)
    except BridgeError as exc: return exc.tool_result()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--game-dir", type=Path, help="Paired instance directory; overrides MINECRAFT_GAME_DIR")
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("mcp")
    for action in ("read", "write", "call"):
        p = sub.add_parser(action)
        p.add_argument("operation")
        p.add_argument("--input", type=Path, help="JSON argument file; avoids shell escaping")
    p = sub.add_parser("script")
    p.add_argument("file", type=Path)
    p.add_argument("--side", choices=("client", "server"), default="server")
    p = sub.add_parser("view")
    p.add_argument("--output")
    p = sub.add_parser("export")
    p.add_argument("spec")
    p.add_argument("output_dir")
    p.add_argument("--formats", nargs="+", default=["litematic", "schem", "nbt"])
    p = sub.add_parser("inspect-file")
    p.add_argument("file", type=Path)
    p = sub.add_parser("place")
    p.add_argument("file")
    p.add_argument("--origin", type=int, nargs=3, required=True)
    p.add_argument("--dimension", default="minecraft:overworld")
    p.add_argument("--include-air", action="store_true")
    args = parser.parse_args()
    if args.game_dir:
        os.environ["MINECRAFT_GAME_DIR"] = str(args.game_dir.expanduser().resolve())
    if args.action == "mcp":
        mcp.run(transport="stdio")
        return
    if args.action in {"read", "write", "call"}:
        payload = json.loads(args.input.read_text(encoding="utf-8-sig")) if args.input else {}
        result = {"read": read, "write": write, "call": rpc}[args.action](args.operation, payload, args.port)
    elif args.action == "script":
        result = script(args.file.read_text(encoding="utf-8-sig"), args.side, args.port)
    elif args.action == "view":
        result = view(args.output, args.port)
    elif args.action == "export":
        result = export(args.spec, args.output_dir, args.formats)
    elif args.action == "inspect-file":
        result = read_build(args.file)
    else:
        result = place(args.file, args.origin, args.dimension, args.include_air, args.port)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except BridgeError as exc:
        print(json.dumps(exc.details(), ensure_ascii=False), file=sys.stderr)
        raise SystemExit(1)
    except Exception as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        raise SystemExit(1)
