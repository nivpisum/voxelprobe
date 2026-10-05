"""Join local Mojang ProGuard and MCPConfig TSRG2 names for Forge 1.20.1.

No downloads or third-party packages. The optional --runtime-jar verifies every
emitted member against the real SRG class files without loading or running them.
See src/main/resources/voxel_probe/mapping/notice.txt for source attribution.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import re
import struct
import zipfile
from collections import defaultdict
from pathlib import Path

PRIMITIVES = dict(zip(
    ("void", "boolean", "byte", "char", "short", "int", "long", "float", "double"),
    ("V", "Z", "B", "C", "S", "I", "J", "F", "D"),
))


def descriptor(type_name: str, classes: dict[str, str]) -> str:
    if type_name.endswith("[]"):
        return "[" + descriptor(type_name[:-2], classes)
    return PRIMITIVES.get(type_name) or "L" + classes.get(type_name, type_name).replace(".", "/") + ";"


def read_mojang(path: Path):
    classes, members = {}, []
    owner = None
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        if not line.startswith(" "):
            owner, obfuscated = line.removesuffix(":").split(" -> ")
            classes[owner] = obfuscated
            continue
        left, obfuscated = line.strip().split(" -> ")
        left = re.sub(r"^(?:\d+:\d+:)+", "", left)
        return_type, signature = left.split(" ", 1)
        if "(" in signature:
            match = re.fullmatch(r"([^()]+)\(([^)]*)\)(?::\d+(?::\d+)?)?", signature)
            if not match:
                raise ValueError(f"Unsupported Mojang signature: {line}")
            name, params = match.groups()
            params = params.split(",") if params else []
            members.append(("M", owner, name, params, return_type, obfuscated))
        else:
            members.append(("F", owner, signature, [], return_type, obfuscated))
    return classes, members


def read_tsrg(path: Path):
    fields, methods = {}, {}
    owner = None
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("tsrg2 ") or line.startswith("\t\t"):
            continue
        parts = line.split()
        if not line.startswith("\t"):
            owner = parts[0]
        elif parts[1].startswith("("):
            methods[(owner, parts[0], parts[1])] = parts[2]
        else:
            fields[(owner, parts[0])] = parts[1]
    return fields, methods


def class_members(data: bytes):
    """Read just the JVM constant pool and field/method tables; never execute code."""
    position = 8

    def u2():
        nonlocal position
        value = struct.unpack_from(">H", data, position)[0]
        position += 2
        return value

    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Not a class file")
    count = u2()
    strings = {}
    index = 1
    while index < count:
        tag = data[position]
        position += 1
        if tag == 1:
            length = u2()
            strings[index] = data[position:position + length].decode("utf-8", errors="replace")
            position += length
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            position += 4
        elif tag in (5, 6):
            position += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            position += 2
        elif tag == 15:
            position += 3
        else:
            raise ValueError(f"Unknown constant-pool tag {tag}")
        index += 1
    position += 6  # access flags, this class, superclass
    interfaces = u2()
    position += 2 * interfaces

    def read_members():
        nonlocal position
        members = set()
        for _ in range(u2()):
            u2()  # access flags
            members.add((strings[u2()], strings[u2()]))
            for _ in range(u2()):
                u2()  # attribute name
                length = struct.unpack_from(">I", data, position)[0]
                position += 4 + length
        return members

    return read_members(), read_members()


def generate(mojang: Path, tsrg: Path):
    classes, members = read_mojang(mojang)
    fields, methods = read_tsrg(tsrg)
    rows = defaultdict(dict)
    missing = []
    for kind, owner, name, params, return_type, obfuscated in members:
        if name.startswith("<"):
            continue  # JVM constructors/initializers are never renamed.
        obfuscated_owner = classes[owner].replace(".", "/")
        if kind == "F":
            srg = fields.get((obfuscated_owner, obfuscated))
            key = name
            runtime_descriptor = descriptor(return_type, {})
        else:
            desc = "(" + "".join(descriptor(p, classes) for p in params) + ")" + descriptor(return_type, classes)
            srg = methods.get((obfuscated_owner, obfuscated, desc))
            key = name + "(" + ",".join(params) + ")"
            runtime_descriptor = "(" + "".join(descriptor(p, {}) for p in params) + ")" + descriptor(return_type, {})
        if srg is None:
            missing.append(f"{owner}.{key}")
            continue
        if srg != name:
            # ProGuardParser also retains the last entry when return-only bridge
            # methods share a name and parameters; match that existing contract.
            rows[owner][(kind, key)] = (srg, runtime_descriptor)
    if missing:
        raise ValueError(f"{len(missing)} members could not be joined: {missing[:10]}")
    lines = ["# debugbridge-forge-member-map-v1\t1.20.1"]
    for label, path in (("mojang", mojang), ("mcpconfig", tsrg)):
        lines.append(f"# {label}_sha256\t{hashlib.sha256(path.read_bytes()).hexdigest()}")
    for owner in sorted(rows):
        lines.append("C\t" + owner)
        for (kind, key), (srg, _) in sorted(rows[owner].items()):
            lines.append(f"{kind}\t{key}\t{srg}")
    return rows, ("\n".join(lines) + "\n").encode("utf-8")


def verify_runtime(rows, runtime_jar: Path):
    count = 0
    failures = []
    with zipfile.ZipFile(runtime_jar) as jar:
        for owner, members in rows.items():
            path = owner.replace(".", "/") + ".class"
            try:
                fields, methods = class_members(jar.read(path))
            except KeyError:
                failures.append(f"Missing class {owner}")
                continue
            for (kind, key), expected in members.items():
                actual = fields if kind == "F" else methods
                if expected not in actual:
                    failures.append(f"{owner}.{key} -> {expected}")
                count += 1
    if failures:
        raise ValueError(f"{len(failures)} runtime mismatches: {failures[:15]}")
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mojang", type=Path, required=True)
    parser.add_argument("--srg", type=Path, required=True, help="MCPConfig TSRG2 file (obf first, SRG second)")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "src/main/resources/voxel_probe/mapping/forge_1_20_1.tsv.gz")
    parser.add_argument("--runtime-jar", type=Path, help="Optional actual Minecraft client SRG jar, read-only")
    parser.add_argument("--check", action="store_true", help="Verify the existing output without writing it")
    args = parser.parse_args()
    rows, text = generate(args.mojang, args.srg)
    # Explicit filename/mtime keep the header independent of the source path,
    # host OS, and Python's gzip.compress implementation.
    compressed = io.BytesIO()
    with gzip.GzipFile(filename="", fileobj=compressed, mode="wb", compresslevel=9, mtime=0) as stream:
        stream.write(text)
    encoded = compressed.getvalue()
    runtime_count = verify_runtime(rows, args.runtime_jar) if args.runtime_jar else None
    if args.check:
        if args.output.read_bytes() != encoded:
            raise ValueError("Generated mapping differs from the checked-in resource")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(encoded)
    print(json.dumps({
        "classes": len(rows),
        "fields": sum(kind == "F" for members in rows.values() for kind, _ in members),
        "methods": sum(kind == "M" for members in rows.values() for kind, _ in members),
        "resource_bytes": len(encoded),
        "runtime_members_verified": runtime_count,
        "checked_without_writing": args.check,
    }))


if __name__ == "__main__":
    main()
