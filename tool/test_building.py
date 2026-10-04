# SPDX-License-Identifier: MIT
# Copyright (c) 2026 nivpisum
"""File-level interchange checks; these do not prove in-game placement/rendering.

Run with the companion environment: python -m unittest discover -s tool -p test_building.py
Temporary fixtures stay in this package's generated build/tool_test directory.
"""

import copy
import itertools
import json
import tempfile
import unittest
from pathlib import Path

import nbtlib as nbt

from building import BuildError, DATA_VERSION, export_build, read_build


def content(spec):
    blocks = []
    for record in spec["blocks"]:
        record = record.copy()
        if "nbt" in record:
            record["nbt"] = nbt.parse_nbt(record["nbt"])
        blocks.append(record)
    return spec["bounds"], blocks


def litematic_fixture(regions, name="regions"):
    """Hand-authored NBT fixture, independent of the production encoder."""
    return nbt.File({
        "Version": nbt.Int(6), "SubVersion": nbt.Int(1), "MinecraftDataVersion": nbt.Int(DATA_VERSION),
        "Metadata": nbt.Compound({"Name": nbt.String(name)}),
        "Regions": nbt.Compound({key: nbt.parse_nbt(value) for key, value in regions.items()}),
    }, gzipped=True)


class BuildingFilesTest(unittest.TestCase):
    def setUp(self):
        scratch = Path(__file__).resolve().parents[1] / "build" / "tool_test"
        scratch.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="building_test_", dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.example = json.loads(Path(__file__).with_name("example_build.json").read_text(encoding="utf-8"))

    def export(self, spec=None, formats=None, subdir="output"):
        return export_build(spec or self.example, self.directory / subdir, formats or ["litematic", "schem", "nbt"])

    def test_example_all_format_roundtrips_and_typed_block_entities(self):
        result = self.export()
        self.assertEqual(result["bounds"], {"min": [-3, 0, -3], "size": [7, 5, 6]})
        self.assertEqual(result["block_count"], 85)
        reference = None
        for fmt, file in result["files"].items():
            with self.subTest(format=fmt):
                path = Path(file)
                self.assertEqual(path.read_bytes()[:2], b"\x1f\x8b")
                root = nbt.load(path)
                self.assertEqual(int(root["MinecraftDataVersion" if fmt == "litematic" else "DataVersion"]), DATA_VERSION)
                read = read_build(path)
                self.assertEqual(read["name"], "oak_shelter")
                actual = content(read)
                if reference is None:
                    reference = actual
                self.assertEqual(actual, reference)
                by_pos = {(b["x"], b["y"], b["z"]): b for b in read["blocks"]}
                self.assertIn("facing=south", by_pos[0, 0, -3]["block"])
                chest = nbt.parse_nbt(by_pos[1, 1, 0]["nbt"])
                self.assertIsInstance(chest["Items"][0]["Count"], nbt.Byte)
                self.assertEqual(int(chest["Items"][0]["Count"]), 17)
                self.assertEqual(str(chest["Items"][0]["id"]), "minecraft:diamond")
                sign = nbt.parse_nbt(by_pos[-1, 1, 0]["nbt"])
                self.assertEqual(json.loads(str(sign["front_text"]["messages"][1])), {"text": "17 diamonds"})
                self.assertEqual(len(sign["back_text"]["messages"]), 4)
                self.assertIsInstance(sign["is_waxed"], nbt.Byte)
                again = self.export(read, subdir=f"again_{fmt}")
                for second in again["files"].values():
                    self.assertEqual(content(read_build(Path(second))), reference)
        sponge = nbt.load(result["files"]["schem"])
        self.assertEqual(sponge.root_name, "Schematic")
        self.assertEqual(int(sponge["Version"]), 2)
        self.assertEqual(list(sponge["Offset"]), [-3, 0, -3])
        for entity in sponge["BlockEntities"]:
            self.assertIn("Id", entity)
            self.assertNotIn("id", entity)
            self.assertIsInstance(entity["Pos"], nbt.IntArray)
        structure = nbt.load(result["files"]["nbt"])
        self.assertEqual(len(structure["blocks"]), 210)
        self.assertTrue(all(int(v) >= 0 for b in structure["blocks"] for v in b["pos"]))
        # These first two packed longs were captured from the pre-replacement
        # example. Their 3-bit entries cross bit 63 and include a signed long.
        litematic = nbt.load(result["files"]["litematic"])["Regions"]["main"]
        self.assertEqual([int(v) for v in litematic["BlockStates"][:2]],
                         [-7905747460161533952, 658812288346769700])

    def test_sponge_varints_beyond_signed_byte_and_external_sparse_palette(self):
        properties = itertools.product(("oak", "spruce"), ("north", "east", "south", "west"), ("top", "bottom"), ("straight", "inner_left", "inner_right", "outer_left", "outer_right"), ("true", "false"))
        blocks = [{"x": i, "y": 0, "z": 0, "block": f"minecraft:{wood}_stairs[facing={facing},half={half},shape={shape},waterlogged={wet}]"}
                  for i, (wood, facing, half, shape, wet) in enumerate(itertools.islice(properties, 130))]
        result = self.export({"name": "palette", "blocks": blocks}, ["schem", "litematic", "nbt"])
        root = nbt.load(result["files"]["schem"])
        self.assertEqual(int(root["PaletteMax"]), 131)
        self.assertEqual(len(root["BlockData"]), 133)
        self.assertEqual([int(v) & 255 for v in root["BlockData"][-6:]], [128, 1, 129, 1, 130, 1])
        self.assertEqual(len(read_build(Path(result["files"]["schem"]))["blocks"]), 130)
        reference = content(read_build(Path(result["files"]["schem"])))
        for filename in result["files"].values():
            self.assertEqual(content(read_build(Path(filename))), reference)
        # Independently reconstruct a single integer from the NBT longs, then
        # slice 8-bit entries. This also verifies high-bit signed-long handling.
        region = nbt.load(result["files"]["litematic"])["Regions"]["main"]
        stream = sum((int(word) & ((1 << 64) - 1)) << (64 * i)
                     for i, word in enumerate(region["BlockStates"]))
        self.assertEqual([(stream >> (8 * i)) & 255 for i in range(130)], list(range(1, 131)))
        root["Width"] = nbt.Short(1)
        root["Palette"] = nbt.Compound({"minecraft:diamond_block": nbt.Int(128)})
        root["PaletteMax"] = nbt.Int(129)
        root["BlockData"] = nbt.ByteArray([-128, 1])
        path = self.directory / "external.schem"
        root.save(path)
        self.assertEqual(read_build(path)["blocks"][0]["block"], "minecraft:diamond_block")

    def test_negative_region_dimensions_and_multiple_regions(self):
        path = self.directory / "regions.litematic"
        litematic_fixture({
            "negative": '{Position:{x:-4,y:2,z:7},Size:{x:-2,y:1,z:-2},'
                        'BlockStatePalette:[{Name:"minecraft:air"},{Name:"minecraft:chest",'
                        'Properties:{facing:"west",type:"single",waterlogged:"false"}}],'
                        'BlockStates:[L;1L],TileEntities:[{id:"minecraft:chest",x:0,y:0,z:0,'
                        'Items:[{Slot:0b,id:"minecraft:diamond",Count:17b}]}]}',
            "other": '{Position:{x:2,y:-1,z:-2},Size:{x:1,y:1,z:1},'
                     'BlockStatePalette:[{Name:"minecraft:air"},{Name:"minecraft:stone"}],BlockStates:[L;1L]}',
        }).save(path)
        spec = read_build(path)
        self.assertEqual(spec["bounds"], {"min": [-5, -1, -2], "size": [8, 4, 10]})
        self.assertEqual({(r["x"], r["y"], r["z"]) for r in spec["blocks"]}, {(-5, 2, 6), (2, -1, -2)})
        self.assertTrue(any("flattened" in w for w in spec["warnings"]))
        chest = next(b for b in spec["blocks"] if b["block"].startswith("minecraft:chest"))
        self.assertEqual(int(nbt.parse_nbt(chest["nbt"])["Items"][0]["Count"]), 17)
        for output in self.export(spec)["files"].values():
            self.assertEqual(content(read_build(Path(output))), content(spec))
        root = nbt.load(path)
        root["Version"] = nbt.Int(5)
        root.pop("SubVersion")
        root.save(path)
        self.assertEqual(content(read_build(path)), content(spec))

    def test_conflicting_overlap_including_air_is_rejected(self):
        path = self.directory / "overlap.litematic"
        litematic_fixture({
            "stone": '{Position:{x:0,y:0,z:0},Size:{x:1,y:1,z:1},'
                     'BlockStatePalette:[{Name:"minecraft:air"},{Name:"minecraft:stone"}],BlockStates:[L;1L]}',
            "air": '{Position:{x:0,y:0,z:0},Size:{x:1,y:1,z:1},'
                   'BlockStatePalette:[{Name:"minecraft:air"}],BlockStates:[L;0L]}',
        }).save(path)
        with self.assertRaisesRegex(BuildError, "overlapping"):
            read_build(path)

    def test_entities_ticks_biomes_and_wrong_data_version_are_not_dropped(self):
        result = self.export()
        for fmt, filename in result["files"].items():
            root = nbt.load(filename)
            container = root["Regions"]["main"] if fmt == "litematic" else root
            key = "entities" if fmt == "nbt" else "Entities"
            container[key] = nbt.List[nbt.Compound]([nbt.Compound({"id": nbt.String("minecraft:pig")})])
            bad = self.directory / f"entity.{fmt}"
            root.save(bad)
            with self.assertRaisesRegex(BuildError, "not supported"):
                read_build(bad)
        root = nbt.load(result["files"]["litematic"])
        root["Regions"]["main"]["PendingBlockTicks"] = nbt.List[nbt.Compound]([nbt.Compound({"t": nbt.Int(1)})])
        bad = self.directory / "ticks.litematic"
        root.save(bad)
        with self.assertRaisesRegex(BuildError, "PendingBlockTicks"):
            read_build(bad)
        root["Regions"]["main"].pop("PendingBlockTicks")
        root["Regions"]["main"]["PendingFluidTicks"] = nbt.List[nbt.Compound]([nbt.Compound({"t": nbt.Int(1)})])
        root.save(bad)
        with self.assertRaisesRegex(BuildError, "PendingFluidTicks"):
            read_build(bad)
        root["Regions"]["main"].pop("PendingFluidTicks")
        root["Regions"]["main"]["BiomeData"] = nbt.ByteArray([0])
        root.save(bad)
        with self.assertRaisesRegex(BuildError, "BiomeData"):
            read_build(bad)
        root = nbt.load(result["files"]["nbt"])
        root["ticks"] = nbt.List[nbt.Compound]([nbt.Compound({"t": nbt.Int(1)})])
        bad = self.directory / "ticks.nbt"
        root.save(bad)
        with self.assertRaisesRegex(BuildError, "ticks"):
            read_build(bad)
        root = nbt.load(result["files"]["schem"])
        root["BiomeData"] = nbt.ByteArray([0])
        bad = self.directory / "biome.schem"
        root.save(bad)
        with self.assertRaisesRegex(BuildError, "BiomeData"):
            read_build(bad)
        root.pop("BiomeData")
        root["DataVersion"] = nbt.Int(4671)
        root.save(bad)
        with self.assertRaisesRegex(BuildError, "migrate"):
            read_build(bad)

    def test_air_bounds_and_arbitrary_nbt_types_survive(self):
        spec = {"name": "typed", "blocks": [{"x": -2, "y": -3, "z": -4, "block": "minecraft:chest", "nbt": '{id:"minecraft:chest",Items:[],Custom:{b:-7b,s:42s,i:99,l:9223372036854775806L,f:1.25f,d:2.5d,ba:[B;1b,-2b],ia:[I;1,-2],la:[L;1L,-2L]}}'}, {"x": 0, "y": 0, "z": 0, "block": "minecraft:air"}]}
        expected = nbt.parse_nbt(spec["blocks"][0]["nbt"])
        for path in self.export(spec)["files"].values():
            read = read_build(Path(path))
            self.assertEqual(read["bounds"], {"min": [-2, -3, -4], "size": [3, 4, 5]})
            actual = nbt.parse_nbt(read["blocks"][0]["nbt"])
            self.assertEqual(actual, expected)
            for key, tag in expected["Custom"].items():
                self.assertIs(type(actual["Custom"][key]), type(tag))
        empty = {"name": "air", "bounds": {"min": [-2, -1, 4], "size": [2, 3, 1]}, "blocks": []}
        for path in self.export(empty, subdir="empty")["files"].values():
            read = read_build(Path(path))
            self.assertEqual(read["blocks"], [])
            self.assertEqual(read["bounds"], empty["bounds"])

    def test_sparse_native_and_truncated_varint_are_rejected(self):
        result = self.export()
        root = nbt.load(result["files"]["nbt"])
        root["blocks"].pop()
        path = self.directory / "sparse.nbt"
        root.save(path)
        with self.assertRaisesRegex(BuildError, "Sparse"):
            read_build(path)
        root = nbt.load(result["files"]["schem"])
        root["BlockData"] = nbt.ByteArray([-128])
        path = self.directory / "truncated.schem"
        root.save(path)
        with self.assertRaisesRegex(BuildError, "truncated"):
            read_build(path)
        root = nbt.load(result["files"]["litematic"])
        root["Regions"]["main"]["BlockStates"] = nbt.LongArray([])
        path = self.directory / "truncated.litematic"
        root.save(path)
        with self.assertRaisesRegex(BuildError, "truncated"):
            read_build(path)
        root["Regions"]["main"]["BlockStates"] = nbt.LongArray([7] * 10)
        root.save(path)
        with self.assertRaisesRegex(BuildError, "palette ID"):
            read_build(path)

    def test_bad_input_and_existing_outputs_are_rejected_before_writes(self):
        cases = [
            {"entities": [{"id": "minecraft:pig"}]},
            {"mc_version": "1.21"},
            {"blocks": [{"x": 0.1, "y": 0, "z": 0, "block": "minecraft:stone"}]},
            {"blocks": [{"x": 0, "y": 0, "z": 0, "block": "minecraft:chest", "nbt": {"id": "minecraft:chest"}}]},
            {"bounds": {"min": [0, 0, 0], "size": [1000, 1000, 1000]}},
        ]
        for update in cases:
            with self.subTest(update=update):
                spec = copy.deepcopy(self.example)
                spec.update(update)
                with self.assertRaises(BuildError):
                    self.export(spec)
                self.assertFalse((self.directory / "output").exists())
        self.export()
        with self.assertRaises(FileExistsError):
            self.export()


if __name__ == "__main__":
    unittest.main()
