#!/usr/bin/env python3
"""Builds src/main/resources/assets/p2ppvp/arena/void_world_gen_settings.dat.

An empty world: flat generator with one air layer, the_void biome, no structures
(generate_structures=0 and an empty structure_overrides list), so it loads instantly.
"""
import gzip, struct, sys

def s(x):
    b = x.encode()
    return struct.pack(">H", len(b)) + b

def named(tag, name, payload):
    return bytes([tag]) + s(name) + payload

def byte(name, v):   return named(1, name, struct.pack(">b", v))
def integer(name, v): return named(3, name, struct.pack(">i", v))
def long_(name, v):  return named(4, name, struct.pack(">q", v))
def string(name, v): return named(8, name, s(v))
def comp(name, *children): return named(10, name, b"".join(children) + b"\x00")
def lst(name, elem_tag, items): return named(9, name, bytes([elem_tag]) + struct.pack(">i", len(items)) + b"".join(items))

def gen_noise(biome_source, settings):
    return comp("generator", string("type", "minecraft:noise"), biome_source, string("settings", settings))

nether = comp("minecraft:the_nether",
    string("type", "minecraft:the_nether"),
    gen_noise(comp("biome_source", string("type", "minecraft:multi_noise"), string("preset", "minecraft:nether")), "minecraft:nether"))
end = comp("minecraft:the_end",
    string("type", "minecraft:the_end"),
    gen_noise(comp("biome_source", string("type", "minecraft:the_end")), "minecraft:end"))
overworld = comp("minecraft:overworld",
    string("type", "minecraft:overworld"),
    comp("generator",
        string("type", "minecraft:flat"),
        comp("settings",
            byte("features", 0),
            byte("lakes", 0),
            lst("layers", 10, [string("block", "minecraft:air") + integer("height", 1) + b"\x00"]),
            string("biome", "minecraft:the_void"),
            lst("structure_overrides", 0, []))))

root = comp("",
    integer("DataVersion", 4790),
    comp("data",
        byte("generate_structures", 0),
        long_("seed", 0),
        comp("dimensions", nether, overworld, end),
        byte("bonus_chest", 0)))

out = sys.argv[1] if len(sys.argv) > 1 else "src/main/resources/assets/p2ppvp/arena/void_world_gen_settings.dat"
with open(out, "wb") as f:
    f.write(gzip.compress(root))
print("wrote", out, len(gzip.compress(root)), "bytes")
