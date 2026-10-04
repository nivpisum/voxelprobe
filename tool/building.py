# SPDX-License-Identifier: MIT
# Copyright (c) 2026 nivpisum
"""Small Minecraft 1.20.1 building interchange layer.

Spec: {name, mc_version: "1.20.1", blocks: [{x, y, z, block, nbt?}]}.
``block`` is a namespaced block-state string; ``nbt`` is an SNBT compound
with an explicit block entity ``id``. SNBT preserves numeric/array types.
Optional ``fills`` contain {from: [x,y,z], to: [x,y,z], block}; endpoints
are inclusive, fills run in order, and individual blocks override them.
Optional ``bounds`` is {min: [x,y,z], size: [w,h,l]}. Otherwise it is inferred.
Unspecified cells inside bounds are air. Reads omit air but retain bounds.

Negative coordinates survive round trips. Native structures use local
nonnegative coordinates and a DebugBridge tag to remember the original
origin; vanilla ignores that tag and places the minimum corner at its anchor.
Entities, biomes, scheduled ticks, ambiguous overlaps and sparse native
structures are rejected rather than silently changed. This validates syntax,
not the target game's block registry. No files are deployed or overwritten.

Litematic and Sponge v2 encoding are implemented here using nbtlib.
Format references consulted (documentation only; no library implementation
code was copied):
https://litemapy.readthedocs.io/en/latest/litematics.html
https://github.com/SpongePowered/Schematic-Specification/blob/master/versions/schematic-2.md
Packed Litematic data and minimum-corner block entity positions were also
checked against pre-existing NBT fixtures. Entries use x + z*w + y*w*l and
a continuous low-bit-first stream, including entries spanning two longs.
"""

from __future__ import annotations

import copy
import gzip
import math
import re
from pathlib import Path

import nbtlib as nbt

MC_VERSION = "1.20.1"
DATA_VERSION = 3465
MAX_VOLUME = 1_000_000
AIR = "minecraft:air"
FORMATS = ("litematic", "schem", "nbt")
_ID = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+\Z")
_STATE = re.compile(r"([a-z0-9_.-]+:[a-z0-9_./-]+)(?:\[([^\[\]]*)\])?\Z")
_UNSUPPORTED_CONTENT = ("Entities", "entities", "PendingBlockTicks", "PendingFluidTicks",
                        "ticks", "Ticks", "Biomes", "biomes", "BiomeData", "BiomePalette")


class BuildError(ValueError):
    """Invalid input or an unsupported conversion that would lose content."""


def _integer(value, label):
    if isinstance(value, bool) or not isinstance(value, (int, nbt.Int, nbt.Short, nbt.Long)):
        raise BuildError(f"{label} must be an integer")
    value = int(value)
    if not -(2**31) <= value < 2**31:
        raise BuildError(f"{label} is outside the signed 32-bit coordinate range")
    return value


def _xyz(value, label):
    if not hasattr(value, "__len__") or isinstance(value, (str, dict)) or len(value) != 3:
        raise BuildError(f"{label} must contain three integers")
    return tuple(_integer(v, label) for v in value)


def _size(value):
    size = _xyz(value, "size")
    if any(v <= 0 or v > 32767 for v in size) or math.prod(size) > MAX_VOLUME:
        raise BuildError(f"size must be positive, <=32767 per axis and <={MAX_VOLUME} cells")
    return size


def _state(value):
    match = _STATE.fullmatch(value) if isinstance(value, str) else None
    if match is None:
        raise BuildError(f"Invalid namespaced block state: {value!r}")
    properties = {}
    if match[2]:
        for entry in match[2].split(","):
            pair = entry.split("=")
            if len(pair) != 2 or not all(re.fullmatch(r"[a-z0-9_.-]+", p) for p in pair):
                raise BuildError(f"Invalid block property: {entry!r}")
            key, val = pair
            if key in properties:
                raise BuildError(f"Duplicate block property: {key}")
            properties[key] = val
    suffix = ",".join(f"{key}={val}" for key, val in sorted(properties.items()))
    return match[1] + (f"[{suffix}]" if suffix else "")


def _identifier(state):
    return _state(state)


def _state_nbt(state):
    match = _STATE.fullmatch(state)
    tag = nbt.Compound({"Name": nbt.String(match[1])})
    if match[2]:
        tag["Properties"] = nbt.Compound({key: nbt.String(val)
                                          for key, val in (pair.split("=") for pair in match[2].split(","))})
    return tag


def _state_from_nbt(tag):
    if not isinstance(tag, nbt.Compound) or set(tag) - {"Name", "Properties"}:
        raise BuildError("Block state must contain only Name and optional Properties")
    if not isinstance(tag.get("Name"), nbt.String):
        raise BuildError("Block state Name must be a string")
    properties = tag.get("Properties", nbt.Compound())
    if not isinstance(properties, nbt.Compound) or any(not isinstance(v, nbt.String) for v in properties.values()):
        raise BuildError("Block state Properties must be a compound of strings")
    suffix = ",".join(f"{key}={val}" for key, val in sorted(properties.items()))
    return _state(str(tag["Name"]) + (f"[{suffix}]" if suffix else ""))


def _pack_states(values, palette_size):
    bits = max(2, (palette_size - 1).bit_length())
    words = [0] * ((len(values) * bits + 63) // 64)
    for i, value in enumerate(values):
        word, offset = divmod(i * bits, 64)
        words[word] |= (value << offset) & ((1 << 64) - 1)
        if offset + bits > 64:
            words[word + 1] |= value >> (64 - offset)
    return nbt.LongArray([word if word < 1 << 63 else word - (1 << 64) for word in words])


def _unpack_states(data, palette_size, count):
    if not palette_size or palette_size > MAX_VOLUME + 1:
        raise BuildError("Litematic palette is empty or exceeds the cell limit")
    bits = max(2, (palette_size - 1).bit_length())
    if not isinstance(data, nbt.LongArray) or len(data) != (count * bits + 63) // 64:
        raise BuildError("Litematic BlockStates is truncated or has the wrong volume")
    words = [int(word) & ((1 << 64) - 1) for word in data]
    mask = (1 << bits) - 1
    for i in range(count):
        word, offset = divmod(i * bits, 64)
        value = words[word] >> offset
        if offset + bits > 64:
            value |= words[word + 1] << (64 - offset)
        value &= mask
        if value >= palette_size:
            raise BuildError(f"Missing Litematic palette ID {value}")
        yield value


def _position_nbt(position):
    return nbt.Compound({key: nbt.Int(value) for key, value in zip(("x", "y", "z"), position)})


def _block_nbt(value):
    if not isinstance(value, str):
        raise BuildError("nbt must be an SNBT compound string, not an untyped JSON object")
    try:
        tag = nbt.parse_nbt(value)
    except Exception as exc:
        raise BuildError(f"Invalid block entity SNBT: {exc}") from exc
    if not isinstance(tag, nbt.Compound) or not isinstance(tag.get("id"), nbt.String):
        raise BuildError("Block entity NBT must be a compound with a string id")
    if not _ID.fullmatch(str(tag["id"])):
        raise BuildError("Block entity id must be namespaced")
    if {"x", "y", "z", "Pos", "Id"} & tag.keys():
        raise BuildError("Block entity positions belong in x/y/z of the block, not its nbt")
    return tag


def _record(position, state, tag=None):
    result = dict(zip(("x", "y", "z"), position))
    result["block"] = _identifier(state)
    if tag is not None:
        tag = copy.deepcopy(tag)
        for key in ("x", "y", "z"):
            tag.pop(key, None)
        result["nbt"] = _block_nbt(tag.snbt()).snbt()
        if result["block"] == AIR:
            raise BuildError(f"Block entity is attached to air at {position}")
    return result


def _reject_content(container, keys):
    for key in keys:
        if key in container and len(container[key]):
            raise BuildError(f"{key} is not supported; conversion would lose content")


def _inside(position, origin, size):
    return all(a <= p < a + s for p, a, s in zip(position, origin, size))


def _normalise(spec):
    if not isinstance(spec, dict):
        raise BuildError("Build spec must be a JSON object")
    if spec.get("mc_version", MC_VERSION) != MC_VERSION or spec.get("data_version", DATA_VERSION) != DATA_VERSION:
        raise BuildError(f"Only Minecraft {MC_VERSION} / DataVersion {DATA_VERSION} is supported")
    _reject_content(spec, ("entities", "biomes", "ticks"))
    allowed = {"name", "mc_version", "data_version", "blocks", "fills", "bounds", "warnings", "source", "entities", "biomes", "ticks"}
    if spec.keys() - allowed:
        raise BuildError(f"Unknown build fields: {sorted(spec.keys() - allowed)}")
    name = spec.get("name", "building")
    if not isinstance(name, str) or not name.strip():
        raise BuildError("name must be a nonempty string")
    cells = {}
    for fill in spec.get("fills", []):
        if set(fill) != {"from", "to", "block"}:
            raise BuildError("Each fill requires exactly from, to and block")
        a, b = _xyz(fill["from"], "fill.from"), _xyz(fill["to"], "fill.to")
        low = tuple(min(x, y) for x, y in zip(a, b))
        high = tuple(max(x, y) for x, y in zip(a, b))
        _size(tuple(y - x + 1 for x, y in zip(low, high)))
        state = _state(fill["block"])
        for y in range(low[1], high[1] + 1):
            for z in range(low[2], high[2] + 1):
                for x in range(low[0], high[0] + 1):
                    cells[x, y, z] = (state, None)
        if len(cells) > MAX_VOLUME:
            raise BuildError("Too many fill cells")
    seen = set()
    for block in spec.get("blocks", []):
        if not isinstance(block, dict) or set(block) - {"x", "y", "z", "block", "nbt"} or not {"x", "y", "z", "block"} <= block.keys():
            raise BuildError("Each block requires x, y, z, block and optional nbt")
        pos = _xyz([block[k] for k in ("x", "y", "z")], "block position")
        if pos in seen:
            raise BuildError(f"Duplicate block position: {pos}")
        seen.add(pos)
        state = _state(block["block"])
        tag = _block_nbt(block["nbt"]) if "nbt" in block else None
        if state == AIR and tag is not None:
            raise BuildError(f"Block entity is attached to air at {pos}")
        cells[pos] = (state, tag)
    if "bounds" in spec:
        origin = _xyz(spec["bounds"]["min"], "bounds.min")
        size = _size(spec["bounds"]["size"])
    elif cells:
        origin = tuple(min(p[i] for p in cells) for i in range(3))
        size = _size(tuple(max(p[i] for p in cells) - origin[i] + 1 for i in range(3)))
    else:
        raise BuildError("An empty build requires explicit bounds")
    _xyz(tuple(a + s - 1 for a, s in zip(origin, size)), "maximum coordinate")
    if any(not _inside(p, origin, size) for p in cells):
        raise BuildError("A block is outside bounds")
    warnings = spec.get("warnings", [])
    if not isinstance(warnings, list) or any(not isinstance(w, str) for w in warnings):
        raise BuildError("warnings must be a list of strings")
    return name, origin, size, cells, warnings[:]


def _positions(size):
    for y in range(size[1]):
        for z in range(size[2]):
            for x in range(size[0]):
                yield x, y, z


def _varints(values):
    data = []
    for value in values:
        while value >= 128:
            data.append((value & 127) | 128)
            value >>= 7
        data.append(value)
    return nbt.ByteArray([b if b < 128 else b - 256 for b in data])


def _decode_varints(data, count):
    result, value, shift = [], 0, 0
    for raw in data:
        byte = int(raw) & 255
        if shift == 28 and byte > 7:
            raise BuildError("Invalid Sponge palette VarInt")
        value |= (byte & 127) << shift
        if byte & 128:
            shift += 7
            if shift > 28:
                raise BuildError("Invalid Sponge palette VarInt")
        else:
            result.append(value)
            value = shift = 0
            if len(result) > count:
                raise BuildError("Sponge BlockData has too many entries")
    if shift or len(result) != count:
        raise BuildError("Sponge BlockData is truncated or has the wrong volume")
    return result


def export_build(spec: dict, output_dir: Path, formats: list[str]) -> dict:
    """Export new files and return paths, bounds and conversion notes.

    Formats are litematic, schem and nbt (structure is an alias for nbt).
    Existing files are never replaced. Output paths are absolute.
    """
    name, origin, size, cells, warnings = _normalise(spec)
    formats = list(dict.fromkeys("nbt" if f == "structure" else f for f in formats))
    if not formats or any(f not in FORMATS for f in formats):
        raise BuildError(f"formats must be a nonempty list from {FORMATS}")
    stem = re.sub(r"[^a-z0-9_]+", "_", name.lower()).strip("_")[:80] or "building"
    if stem in {"con", "prn", "aux", "nul", *(f"com{i}" for i in range(1, 10)), *(f"lpt{i}" for i in range(1, 10))}:
        stem = "building_" + stem
    output_dir = Path(output_dir).resolve()
    paths = {f: output_dir / f"{stem}.{f}" for f in formats}
    for path in paths.values():
        if path.exists():
            raise FileExistsError(f"Refusing to overwrite {path}")
    palette = [AIR]
    lookup = {AIR: 0}
    local = {}
    for pos, (state, tag) in cells.items():
        key = _identifier(state)
        if key not in lookup:
            lookup[key] = len(palette)
            palette.append(state)
        local[tuple(p - a for p, a in zip(pos, origin))] = (lookup[key], tag)
    documents = {}
    if "litematic" in formats:
        entities = nbt.List[nbt.Compound]()
        for pos, (_, tag) in local.items():
            if tag is not None:
                entity = copy.deepcopy(tag)
                entity.update(_position_nbt(pos))
                entities.append(entity)
        region = nbt.Compound({
            "Position": _position_nbt(origin), "Size": _position_nbt(size),
            "BlockStatePalette": nbt.List[nbt.Compound]([_state_nbt(state) for state in palette]),
            "BlockStates": _pack_states([local.get(p, (0, None))[0] for p in _positions(size)], len(palette)),
            "TileEntities": entities, "Entities": nbt.List[nbt.Compound](),
            "PendingBlockTicks": nbt.List[nbt.Compound](), "PendingFluidTicks": nbt.List[nbt.Compound](),
        })
        documents["litematic"] = nbt.Compound({
            "Version": nbt.Int(6), "SubVersion": nbt.Int(1), "MinecraftDataVersion": nbt.Int(DATA_VERSION),
            "Metadata": nbt.Compound({
                "Name": nbt.String(name), "Author": nbt.String(""), "Description": nbt.String(""),
                "EnclosingSize": _position_nbt(size), "RegionCount": nbt.Int(1),
                "TotalVolume": nbt.Int(math.prod(size)), "TotalBlocks": nbt.Int(sum(state != AIR for state, _ in cells.values())),
                "TimeCreated": nbt.Long(0), "TimeModified": nbt.Long(0),
            }),
            "Regions": nbt.Compound({"main": region}),
        })
    if "schem" in formats:
        entities = nbt.List[nbt.Compound]()
        for pos, (_, tag) in local.items():
            if tag is not None:
                tag = copy.deepcopy(tag)
                tag["Id"] = tag.pop("id")
                tag["Pos"] = nbt.IntArray(pos)
                entities.append(tag)
        documents["schem"] = nbt.Compound({
            "Version": nbt.Int(2), "DataVersion": nbt.Int(DATA_VERSION),
            "Width": nbt.Short(size[0]), "Height": nbt.Short(size[1]), "Length": nbt.Short(size[2]),
            "Offset": nbt.IntArray(origin), "Metadata": nbt.Compound({"Name": nbt.String(name)}),
            "PaletteMax": nbt.Int(len(palette)),
            "Palette": nbt.Compound({_identifier(s): nbt.Int(i) for i, s in enumerate(palette)}),
            "BlockData": _varints(local.get(p, (0, None))[0] for p in _positions(size)),
            "BlockEntities": entities, "Entities": nbt.List[nbt.Compound](),
        })
    if "nbt" in formats:
        blocks = nbt.List[nbt.Compound]()
        for pos in _positions(size):
            index, tag = local.get(pos, (0, None))
            block = nbt.Compound({"pos": nbt.List[nbt.Int](pos), "state": nbt.Int(index)})
            if tag is not None:
                block["nbt"] = copy.deepcopy(tag)
            blocks.append(block)
        documents["nbt"] = nbt.Compound({
            "DataVersion": nbt.Int(DATA_VERSION), "size": nbt.List[nbt.Int](size),
            "palette": nbt.List[nbt.Compound]([_state_nbt(s) for s in palette]),
            "blocks": blocks, "entities": nbt.List[nbt.Compound](),
            "DebugBridge": nbt.Compound({"Origin": nbt.IntArray(origin), "Name": nbt.String(name)}),
        })
        if origin != (0, 0, 0):
            warnings.append("Native structure placement uses its minimum corner; DebugBridge.Origin preserves the original coordinates only for this reader.")
        if any(v > 48 for v in size):
            warnings.append("A structure dimension exceeds the vanilla structure block UI limit of 48.")
    output_dir.mkdir(parents=True, exist_ok=True)
    for fmt, path in paths.items():
        with path.open("xb") as raw:
            with gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as compressed:
                nbt.File(documents[fmt], root_name="Schematic" if fmt == "schem" else "").write(compressed)
    return {"name": name, "mc_version": MC_VERSION, "data_version": DATA_VERSION,
            "bounds": {"min": list(origin), "size": list(size)},
            "block_count": sum(state != AIR for state, _ in cells.values()),
            "files": {fmt: str(path) for fmt, path in paths.items()}, "warnings": list(dict.fromkeys(warnings))}


def _read_litematic(root):
    if int(root["Version"]) not in (5, 6):
        raise BuildError("Only Litematic format versions 5 and 6 are supported")
    records, covered, lows, highs = {}, set(), [], []
    regions = root["Regions"]
    if not regions:
        raise BuildError("Litematic has no regions")
    total = 0
    for region_name, raw in regions.items():
        _reject_content(raw, _UNSUPPORTED_CONTENT)
        anchor = _xyz([raw["Position"][k] for k in ("x", "y", "z")], "region position")
        signed = _xyz([raw["Size"][k] for k in ("x", "y", "z")], "region size")
        size = _size(tuple(abs(v) for v in signed))
        low = tuple(a + min(0, s + 1) for a, s in zip(anchor, signed))
        _xyz(low, "region minimum coordinate")
        total += math.prod(size)
        if total > MAX_VOLUME:
            raise BuildError("Litematic regions exceed the cell limit")
        lows.append(low)
        highs.append(tuple(a + s - 1 for a, s in zip(low, size)))
        _xyz(tuple(a + s - 1 for a, s in zip(low, size)), "region maximum coordinate")
        palette = [_state_from_nbt(state) for state in raw["BlockStatePalette"]]
        ids = _unpack_states(raw["BlockStates"], len(palette), math.prod(size))
        entities = {}
        for entity in raw.get("TileEntities", []):
            pos = _xyz([entity[k] for k in ("x", "y", "z")], "block entity position")
            # Litematica 1.20.1 saves block entities relative to the region's
            # minimum corner, even when Size is negative.
            absolute = tuple(a + p for a, p in zip(low, pos))
            if not _inside(pos, (0, 0, 0), size) or absolute in entities:
                raise BuildError(f"Invalid or duplicate block entity in region {region_name}")
            entities[absolute] = entity
        for pos, index in zip(_positions(size), ids):
            absolute = tuple(a + p for a, p in zip(low, pos))
            record = _record(absolute, palette[index], entities.get(absolute))
            if absolute in covered and records.get(absolute, {}).get("block", AIR) != record["block"]:
                raise BuildError(f"Conflicting overlapping regions at {absolute}")
            if absolute in records and records[absolute] != record:
                raise BuildError(f"Conflicting overlapping block entity data at {absolute}")
            covered.add(absolute)
            if record["block"] != AIR:
                records[absolute] = record
    origin = tuple(min(p[i] for p in lows) for i in range(3))
    size = _size(tuple(max(p[i] for p in highs) - origin[i] + 1 for i in range(3)))
    warnings = ["Author, selection, preview and other file metadata are not part of the compact build spec."]
    if len(regions) > 1:
        warnings.append("Multiple regions were flattened; region names are omitted and gaps inside the bounding volume become air on export.")
    return str(root.get("Metadata", {}).get("Name", "building")), origin, size, records, warnings


def _read_sponge(root):
    if int(root["Version"]) != 2:
        raise BuildError("Only Sponge schematic version 2 is supported")
    _reject_content(root, ("Entities", "BiomeData", "BiomePalette"))
    size = _size([int(root[k]) & 65535 for k in ("Width", "Height", "Length")])
    origin = _xyz(root.get("Offset", (0, 0, 0)), "Offset")
    palette = {}
    for state, index in root["Palette"].items():
        index = int(index)
        if index < 0 or index in palette:
            raise BuildError("Sponge palette IDs must be distinct nonnegative integers")
        palette[index] = _state(str(state))
    ids = _decode_varints(root["BlockData"], math.prod(size))
    entities = {}
    for tag in root.get("BlockEntities", []):
        tag = copy.deepcopy(tag)
        pos = _xyz(tag.pop("Pos"), "BlockEntity.Pos")
        if pos in entities or not _inside(pos, (0, 0, 0), size):
            raise BuildError("Invalid or duplicate Sponge block entity position")
        if "id" in tag:
            raise BuildError("Sponge block entity contains ambiguous id and Id")
        tag["id"] = tag.pop("Id")
        entities[pos] = tag
    records = {}
    for pos, index in zip(_positions(size), ids):
        if index not in palette:
            raise BuildError(f"Missing Sponge palette ID {index}")
        absolute = tuple(a + p for a, p in zip(origin, pos))
        record = _record(absolute, palette[index], entities.get(pos))
        if record["block"] != AIR:
            records[absolute] = record
    return str(root.get("Metadata", {}).get("Name", "building")), origin, size, records, ["File metadata other than name and offset is not retained."]


def _read_structure(root, fallback_name):
    _reject_content(root, ("entities",))
    if "palettes" in root:
        raise BuildError("Structure palettes/variants are not supported; select one palette explicitly")
    size = _size(root["size"])
    metadata = root.get("DebugBridge", {})
    origin = _xyz(metadata.get("Origin", (0, 0, 0)), "DebugBridge.Origin")
    palette = [_state_from_nbt(state) for state in root["palette"]]
    records, seen = {}, set()
    for block in root["blocks"]:
        pos = _xyz(block["pos"], "structure block position")
        index = int(block["state"])
        if pos in seen or not _inside(pos, (0, 0, 0), size) or not 0 <= index < len(palette):
            raise BuildError("Invalid or duplicate structure block position/palette index")
        seen.add(pos)
        absolute = tuple(a + p for a, p in zip(origin, pos))
        record = _record(absolute, palette[index], block.get("nbt"))
        if record["block"] != AIR:
            records[absolute] = record
    if len(seen) != math.prod(size):
        raise BuildError("Sparse structure has omitted cells; conversion would replace them with air")
    return str(metadata.get("Name", fallback_name)), origin, size, records, ["Native structure placement uses local coordinates; only this reader restores DebugBridge.Origin."]


def read_build(path: Path) -> dict:
    """Read .litematic, Sponge v2 .schem or native structure .nbt as a spec.

    The returned spec can be passed directly to export_build. Non-block
    entities and unrepresentable content cause BuildError, never silent loss.
    Other Minecraft data versions require external migration first.
    """
    path = Path(path)
    try:
        root = nbt.load(path)
        _reject_content(root, _UNSUPPORTED_CONTENT)
        if "Regions" in root:
            version = root["MinecraftDataVersion"]
            reader = lambda: _read_litematic(root)
        elif "BlockData" in root:
            version = root["DataVersion"]
            reader = lambda: _read_sponge(root)
        elif "blocks" in root and "size" in root:
            version = root["DataVersion"]
            reader = lambda: _read_structure(root, path.stem)
        else:
            raise BuildError("Unrecognised building format (legacy .schematic and Sponge v3 are unsupported)")
        if int(version) != DATA_VERSION:
            raise BuildError(f"DataVersion {int(version)} is not {DATA_VERSION}; migrate to Minecraft {MC_VERSION} first")
        name, origin, size, records, warnings = reader()
        _xyz(tuple(a + s - 1 for a, s in zip(origin, size)), "maximum coordinate")
        blocks = [records[p] for p in sorted(records, key=lambda p: (p[1], p[2], p[0]))]
        return {"name": name, "mc_version": MC_VERSION, "data_version": DATA_VERSION,
                "bounds": {"min": list(origin), "size": list(size)}, "blocks": blocks, "warnings": warnings}
    except BuildError:
        raise
    except (KeyError, IndexError, TypeError, ValueError, EOFError) as exc:
        raise BuildError(f"Malformed building file {path.name}: {exc}") from exc
