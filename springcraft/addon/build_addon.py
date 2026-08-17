#!/usr/bin/env python3
"""SpringCat Minecraft Bedrock addon generator.

Generates an original behavior pack + resource pack (a companion mob
"Spring Cat" and a "Spring Treat" item) from scratch -- all geometry,
textures and JSON below are authored by this script, no Mojang/vanilla
assets are copied. Produces SpringCat.mcaddon.

Usage: python3 build_addon.py
"""
import json
import os
import shutil
import uuid
import zipfile

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "out")
BP = os.path.join(OUT, "BP")
RP = os.path.join(OUT, "RP")

NAMESPACE = "springcat"
ADDON_VERSION = [1, 2, 3]
# v1.2.1 で 1.21.100 まで引き上げたが、これは Mojang/bedrock-samples の
# "preview" ブランチ(製品版にまだ降りていない最新開発版)の内容を参照して
# しまったための誤り。長らく安定して使われている十分に古い基準値まで
# 引き下げてある。
MIN_ENGINE = [1, 21, 0]
ITEM_FORMAT_VERSION = "1.21.0"
# spring_treat だけ minecraft:food.effects を使うため低めの format_version。
# 理由は write_bp_item() のコメント参照。
FOOD_ITEM_FORMAT_VERSION = "1.16.0"
# 実際に Bedrock Dedicated Server 1.26.44.3 でレシピを検証した結果、
# format_version が "1.20" 以降だとレシピに "unlock" フィールドが必須になり
# (無いと "1.20+ Recipes require unlock data" エラーでレシピ自体がロード
# 拒否される)、追加したレシピが全滅していた。バニラの実レシピファイルは
# 今も "1.12" を使い続けており(unlock 必須化の対象外になる)、これに合わせる
# のが最も簡単で安全。
RECIPE_FORMAT_VERSION = "1.12"
# manifest.json の dependencies に書く @minecraft/server のバージョン。
# stable track なので、実機の Minecraft がこれ以上の新しいマイナー/パッチを
# 積んでいれば互換扱いで解決される (SemVer の "^" 相当)。
SCRIPT_API_VERSION = "1.13.0"

# Fixed UUIDs so rebuilding the addon (bumping ADDON_VERSION) keeps the
# same pack identity instead of Minecraft treating it as a brand new pack.
UUIDS = {
    "bp_header": "5b6a2f2a-1c1e-4e2a-8e2b-2a1f9c8d0e10",
    "bp_module": "5b6a2f2a-1c1e-4e2a-8e2b-2a1f9c8d0e11",
    "bp_script_module": "5b6a2f2a-1c1e-4e2a-8e2b-2a1f9c8d0e12",
    "rp_header": "5b6a2f2a-1c1e-4e2a-8e2b-2a1f9c8d0e20",
    "rp_module": "5b6a2f2a-1c1e-4e2a-8e2b-2a1f9c8d0e21",
}
for k, v in UUIDS.items():
    uuid.UUID(v)  # sanity check they're well-formed


def reset_out():
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    for p in [
        BP, os.path.join(BP, "entities"), os.path.join(BP, "items"),
        os.path.join(BP, "blocks"), os.path.join(BP, "recipes"),
        os.path.join(BP, "scripts"), os.path.join(BP, "texts"),
        RP, os.path.join(RP, "entity"), os.path.join(RP, "models", "entity"),
        os.path.join(RP, "textures", "entity"), os.path.join(RP, "textures", "items"),
        os.path.join(RP, "textures", "blocks"), os.path.join(RP, "texts"),
    ]:
        os.makedirs(p, exist_ok=True)


def write_json(path, data):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")


# ---------------------------------------------------------------------------
# manifests
# ---------------------------------------------------------------------------

def write_manifests():
    write_json(os.path.join(BP, "manifest.json"), {
        "format_version": 2,
        "header": {
            "name": "SpringCat Addon [Behavior]",
            "description": "springcat.ragdollp.site 公式アドオン：相棒 Spring Cat と Spring Treat",
            "uuid": UUIDS["bp_header"],
            "version": ADDON_VERSION,
            "min_engine_version": MIN_ENGINE,
        },
        "modules": [
            {"type": "data", "uuid": UUIDS["bp_module"], "version": ADDON_VERSION},
            {
                "type": "script",
                "language": "javascript",
                "uuid": UUIDS["bp_script_module"],
                "entry": "scripts/main.js",
                "version": ADDON_VERSION,
            },
        ],
        "dependencies": [
            {"uuid": UUIDS["rp_header"], "version": ADDON_VERSION},
            {"module_name": "@minecraft/server", "version": SCRIPT_API_VERSION},
        ],
    })

    write_json(os.path.join(RP, "manifest.json"), {
        "format_version": 2,
        "header": {
            "name": "SpringCat Addon [Resources]",
            "description": "springcat.ragdollp.site 公式アドオン：相棒 Spring Cat と Spring Treat",
            "uuid": UUIDS["rp_header"],
            "version": ADDON_VERSION,
            "min_engine_version": MIN_ENGINE,
        },
        "modules": [
            {"type": "resources", "uuid": UUIDS["rp_module"], "version": ADDON_VERSION},
        ],
    })


# ---------------------------------------------------------------------------
# pack icons
# ---------------------------------------------------------------------------

def draw_pack_icon(path):
    size = 128
    img = Image.new("RGBA", (size, size), (232, 116, 59, 255))  # brand accent
    d = ImageDraw.Draw(img)
    # simple flat cat face
    cx, cy = size // 2, size // 2 + 6
    r = 40
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 230, 214, 255))
    # ears
    d.polygon([(cx - r - 4, cy - 10), (cx - r + 26, cy - r + 4), (cx - r + 4, cy - r + 30)],
               fill=(255, 230, 214, 255))
    d.polygon([(cx + r + 4, cy - 10), (cx + r - 26, cy - r + 4), (cx + r - 4, cy - r + 30)],
               fill=(255, 230, 214, 255))
    # eyes
    d.ellipse([cx - 20, cy - 6, cx - 10, cy + 6], fill=(51, 41, 31, 255))
    d.ellipse([cx + 10, cy - 6, cx + 20, cy + 6], fill=(51, 41, 31, 255))
    # nose
    d.polygon([(cx - 4, cy + 10), (cx + 4, cy + 10), (cx, cy + 16)], fill=(232, 116, 59, 255))
    img.save(path)


# ---------------------------------------------------------------------------
# box-uv texture packing helpers (standard Minecraft "box UV" unwrap)
# ---------------------------------------------------------------------------

class UvCursor:
    def __init__(self):
        self.x = 0
        self.max_h = 0

    def place(self, dx, dy, dz):
        fw = 2 * (dx + dz)
        fh = dz + dy
        u, v = self.x, 0
        self.x += fw
        self.max_h = max(self.max_h, fh)
        return u, v, fw, fh


def shade(color, factor):
    return tuple(min(255, max(0, int(c * factor))) for c in color[:3]) + (255,)


def paint_cube(draw, u, v, dx, dy, dz, color):
    """Fill the 6-face box-UV footprint for a cube with shaded flat colors.
    Layout matches Bedrock's automatic per-cube "uv": [u, v] unwrap."""
    top = (u + dz, v, dx, dz)
    bottom = (u + dz + dx, v, dx, dz)
    right = (u, v + dz, dz, dy)
    front = (u + dz, v + dz, dx, dy)
    left = (u + dz + dx, v + dz, dz, dy)
    back = (u + 2 * dz + dx, v + dz, dx, dy)
    faces = {
        "top": (top, shade(color, 1.18)),
        "bottom": (bottom, shade(color, 0.55)),
        "right": (right, shade(color, 0.8)),
        "front": (front, shade(color, 1.0)),
        "left": (left, shade(color, 0.8)),
        "back": (back, shade(color, 0.7)),
    }
    for name, (rect, c) in faces.items():
        x, y, w, h = rect
        draw.rectangle([x, y, x + w - 1, y + h - 1], fill=c)
    return {name: rect for name, (rect, _c) in faces.items()}


# ---------------------------------------------------------------------------
# Spring Cat geometry + texture
# ---------------------------------------------------------------------------

ORANGE = (232, 116, 59)
ORANGE_LIGHT = (240, 149, 95)
CREAM = (253, 247, 240)
DARK = (51, 41, 31)

# (name, parent, size(dx,dy,dz), origin(x,y,z), pivot(x,y,z), rotation, color)
CUBES = [
    ("body", None, (8, 7, 10), (-4, 6, -5), (0, 6, 0), None, ORANGE),
    ("head", "body", (7, 6, 6), (-3.5, 10, -11), (0, 10, -5), None, ORANGE_LIGHT),
    ("ear_left", "head", (2, 2, 1), (-3, 15.6, -10.5), (-2, 15.6, -10), None, ORANGE_LIGHT),
    ("ear_right", "head", (2, 2, 1), (1, 15.6, -10.5), (2, 15.6, -10), None, ORANGE_LIGHT),
    ("tail", "body", (2, 2, 8), (-1, 9, 5), (0, 10, 5), (-25, 0, 0), ORANGE),
    ("leg_front_left", "body", (2, 6, 2), (-3.5, 0, -4), (-2.5, 6, -3), None, ORANGE),
    ("leg_front_right", "body", (2, 6, 2), (1.5, 0, -4), (2.5, 6, -3), None, ORANGE),
    ("leg_back_left", "body", (2, 6, 2), (-3.5, 0, 3), (-2.5, 6, 4), None, ORANGE),
    ("leg_back_right", "body", (2, 6, 2), (1.5, 0, 3), (2.5, 6, 4), None, ORANGE),
]


def build_spring_cat_geo_and_texture():
    cursor = UvCursor()
    placements = {}
    for name, _parent, size, _origin, _pivot, _rot, _color in CUBES:
        dx, dy, dz = size
        u, v, fw, fh = cursor.place(dx, dy, dz)
        placements[name] = (u, v, fw, fh)

    tex_w = cursor.x
    tex_h = cursor.max_h
    img = Image.new("RGBA", (tex_w, tex_h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    face_rects = {}
    for name, _parent, size, _origin, _pivot, _rot, color in CUBES:
        dx, dy, dz = size
        u, v, _fw, _fh = placements[name]
        face_rects[name] = paint_cube(draw, u, v, dx, dy, dz, color)

    # eyes + nose painted onto the head's front face
    hx, hy, hw, hh = face_rects["head"]["front"]
    eye_w = max(1, hw // 6)
    eye_y = hy + hh // 3
    draw.rectangle([hx + hw // 5, eye_y, hx + hw // 5 + eye_w, eye_y + eye_w], fill=DARK)
    draw.rectangle([hx + hw - hw // 5 - eye_w, eye_y, hx + hw - hw // 5, eye_y + eye_w], fill=DARK)
    nose_w = max(1, hw // 6)
    nx = hx + hw // 2 - nose_w // 2
    ny = hy + hh // 2
    draw.rectangle([nx, ny, nx + nose_w, ny + max(1, nose_w // 2)], fill=(232, 150, 150, 255))

    os.makedirs(os.path.join(RP, "textures", "entity"), exist_ok=True)
    tex_path = os.path.join(RP, "textures", "entity", "spring_cat.png")
    img.save(tex_path)

    bones = []
    for name, parent, size, origin, pivot, rot, _color in CUBES:
        u, v, _fw, _fh = placements[name]
        bone = {
            "name": name,
            "pivot": list(pivot),
            "cubes": [
                {
                    "origin": list(origin),
                    "size": list(size),
                    "uv": [u, v],
                }
            ],
        }
        if parent:
            bone["parent"] = parent
        if rot:
            bone["rotation"] = list(rot)
        bones.append(bone)

    geo = {
        "format_version": "1.16.0",
        "minecraft:geometry": [
            {
                "description": {
                    "identifier": "geometry.spring_cat",
                    "texture_width": tex_w,
                    "texture_height": tex_h,
                    "visible_bounds_width": 3,
                    "visible_bounds_height": 2,
                    "visible_bounds_offset": [0, 0.6, 0],
                },
                "bones": bones,
            }
        ],
    }
    write_json(os.path.join(RP, "models", "entity", "spring_cat.geo.json"), geo)


# ---------------------------------------------------------------------------
# Spring Treat item icon
# ---------------------------------------------------------------------------

def build_spring_treat_texture():
    size = 16
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # a little fish-shaped treat
    d.polygon([(2, 8), (11, 4), (13, 8), (11, 12)], fill=ORANGE)
    d.polygon([(2, 8), (0, 5), (0, 11)], fill=ORANGE_LIGHT)  # tail fin
    d.point((10, 6), fill=DARK)  # eye
    d.line([(6, 8), (9, 8)], fill=shade(ORANGE, 0.6))
    path = os.path.join(RP, "textures", "items", "spring_treat.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


# ---------------------------------------------------------------------------
# Spring Turbo Block (無上限加速レール用のトリガーブロック)
# ---------------------------------------------------------------------------

def build_turbo_block_texture():
    """暗い下地に、進行方向を示すオレンジのシェブロン(>>>)柄を描く16x16タイル。
    全6面同じテクスチャを使う単純な立方体ブロック。"""
    size = 16
    img = Image.new("RGBA", (size, size), (40, 32, 26, 255))
    d = ImageDraw.Draw(img)
    # 縁取り
    d.rectangle([0, 0, size - 1, size - 1], outline=shade(ORANGE, 0.6))
    # シェブロン x2
    for ox in (1, 8):
        d.line([(ox, 3), (ox + 4, 8), (ox, 13)], fill=ORANGE, width=2)
    path = os.path.join(RP, "textures", "blocks", "turbo_block.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def write_bp_turbo_block():
    data = {
        "format_version": ITEM_FORMAT_VERSION,
        "minecraft:block": {
            "description": {
                "identifier": f"{NAMESPACE}:turbo_block",
                "menu_category": {"category": "construction"},
            },
            "components": {
                "minecraft:destructible_by_mining": {"seconds_to_destroy": 2},
                "minecraft:destructible_by_explosion": {"explosion_resistance": 10},
                "minecraft:friction": 0.4,
                "minecraft:light_emission": 8,
                "minecraft:map_color": "#e8743b",
                "minecraft:material_instances": {
                    "*": {"texture": "turbo_block", "render_method": "opaque"}
                },
                "minecraft:collision_box": True,
                "minecraft:selection_box": True,
            },
        },
    }
    write_json(os.path.join(BP, "blocks", "turbo_block.json"), data)


def write_bp_turbo_recipe():
    # 実機検証で判明: recipe_shapeless の材料フィールド名は "input" ではなく
    # "ingredients"(公式ドキュメント/実サンプルで確認済み)。"output" も誤りで
    # 正しくは "result"。
    data = {
        "format_version": RECIPE_FORMAT_VERSION,
        "minecraft:recipe_shapeless": {
            "description": {"identifier": f"{NAMESPACE}:turbo_block_from_gold"},
            "tags": ["crafting_table"],
            "ingredients": [
                {"item": "minecraft:gold_block"},
                {"item": f"{NAMESPACE}:spring_treat"},
            ],
            "result": {"item": f"{NAMESPACE}:turbo_block", "count": 4},
        },
    }
    write_json(os.path.join(BP, "recipes", "turbo_block.json"), data)


def write_bp_chainmail_recipes():
    """バニラの chainmail_* はデフォルトでは非クラフト(村人取引/戦利品限定)。
    minecraft:chain(ネザーアップデートで追加された装飾ブロック)を素材として
    実際にクラフトできるようにするレシピを追加する。"""
    # 実機検証で判明: 空きマスは "_" ではなく半角スペース " " が正しい表記
    # (Mojang 公式 iron_pickaxe.json / stone_axe.json で確認済み)。"_" は
    # key に対応するアイテムが無いため、レシピ全体がロードに失敗していた。
    recipes = [
        ("chainmail_helmet", ["XXX", "X X"]),
        ("chainmail_chestplate", ["X X", "XXX", "XXX"]),
        ("chainmail_leggings", ["XXX", "X X", "X X"]),
        ("chainmail_boots", ["X X", "X X"]),
    ]
    for item_name, pattern in recipes:
        data = {
            "format_version": RECIPE_FORMAT_VERSION,
            "minecraft:recipe_shaped": {
                "description": {"identifier": f"{NAMESPACE}:{item_name}_from_chain"},
                "tags": ["crafting_table"],
                "pattern": pattern,
                "key": {"X": {"item": "minecraft:chain"}},
                "result": {"item": f"minecraft:{item_name}", "count": 1},
            },
        }
        write_json(os.path.join(BP, "recipes", f"{item_name}_from_chain.json"), data)


# ---------------------------------------------------------------------------
# Chain tool set (銅と同等ステータス: 耐久191 / 採掘効率5 / エンチャント率13 /
# 採掘レベルはストーン相当) — Minecraft Wiki の Copper Tools 実測値に準拠。
# ---------------------------------------------------------------------------

CHAIN_SILVER = (168, 176, 184)
CHAIN_SILVER_DARK = (96, 102, 110)

CHAIN_TOOLS = {
    # name: (attack_total, digger_rules, enchant_slot, recipe_pattern, tags)
    "chain_pickaxe": {
        "attack_total": 4,
        "enchant_slot": "pickaxe",
        "tags": ["minecraft:is_pickaxe", "minecraft:is_tool", "minecraft:stone_tier"],
        "digger": [
            {"block": {"tags": "query.any_tag('stone')"}, "speed": 5},
            {"block": "minecraft:iron_ore", "speed": 5},
            {"block": "minecraft:copper_ore", "speed": 5},
            {"block": "minecraft:lapis_ore", "speed": 5},
        ],
        "pattern": ["XXX", " # ", " # "],
    },
    "chain_axe": {
        "attack_total": 5,
        "enchant_slot": "axe",
        "tags": ["minecraft:is_axe", "minecraft:is_tool", "minecraft:stone_tier"],
        "digger": [
            {"block": {"tags": "query.any_tag('wood')"}, "speed": 5},
        ],
        "pattern": ["XX", "X#", " #"],
    },
    "chain_shovel": {
        "attack_total": 4,
        "enchant_slot": "shovel",
        "tags": ["minecraft:is_shovel", "minecraft:is_tool", "minecraft:stone_tier"],
        "digger": [
            {"block": {"tags": "query.any_tag('dirt')"}, "speed": 5},
        ],
        "pattern": ["X", "#", "#"],
    },
    "chain_hoe": {
        "attack_total": 4,
        "enchant_slot": "hoe",
        "tags": ["minecraft:is_hoe", "minecraft:is_tool", "minecraft:stone_tier"],
        "digger": [],
        "pattern": ["XX", " #", " #"],
    },
    "chain_sword": {
        "attack_total": 6,
        "enchant_slot": "sword",
        "tags": ["minecraft:is_sword", "minecraft:is_tool"],
        "digger": [
            {"block": "minecraft:web", "speed": 15},
            {"block": "minecraft:bamboo", "speed": 30},
        ],
        "pattern": ["X", "X", "#"],
    },
}

ENCHANTABILITY = 13
DURABILITY = 191


def draw_tool_icon(kind):
    """16x16 の対角ツールアイコンを生成する(バニラ準拠の見た目の簡易版)。"""
    size = 16
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 柄 (斜めの棒)
    for i in range(9):
        x = 2 + i
        y = 13 - i
        d.point((x, y), fill=(120, 84, 48, 255))
        d.point((x, y - 1), fill=(150, 108, 64, 255))

    head = shade(CHAIN_SILVER, 1.0)
    dark = CHAIN_SILVER_DARK + (255,)
    if kind == "pickaxe":
        d.line([(2, 3), (7, 2), (13, 5)], fill=head, width=2)
        d.line([(2, 3), (5, 6)], fill=dark, width=2)
    elif kind == "axe":
        d.polygon([(3, 1), (12, 2), (11, 8), (5, 7)], fill=head)
        d.line([(3, 1), (5, 7)], fill=dark, width=1)
    elif kind == "shovel":
        d.rectangle([2, 1, 7, 6], fill=head)
        d.rectangle([2, 1, 3, 6], fill=dark)
    elif kind == "hoe":
        d.line([(1, 4), (7, 1)], fill=head, width=2)
    elif kind == "sword":
        d.line([(3, 0), (12, 9)], fill=head, width=2)
        d.line([(0, 12), (3, 9)], fill=(150, 108, 64, 255), width=2)
        d.point((11, 11), fill=dark)
    return img


def build_chain_tool_textures():
    for name in CHAIN_TOOLS:
        kind = name.split("_", 1)[1]
        img = draw_tool_icon(kind)
        path = os.path.join(RP, "textures", "items", f"{name}.png")
        img.save(path)


def write_bp_chain_tools():
    # 実際に Bedrock Dedicated Server 1.26.44.3 を起動し、コンテンツログの
    # [WARN]/[ERROR] を見ながら検証して確定した形式:
    #  - minecraft:icon コンポーネントは省略する。実際のバニラアイテムは
    #    どれも minecraft:icon を書いておらず(item_texture.json 側の
    #    キー名がアイテム識別子の短縮名と一致していれば自動解決される)、
    #    こちらで試した {"texture": ...} 系の書き方は全部スキーマ警告
    #    (Schema に存在しないメンバー扱いで無視される)になっていた。
    #    これがテクスチャが正しく表示されなかった原因。
    #  - description.category は同様にスキーマに存在せず無視されていた。
    #    正しくは description.menu_category.category。
    #  - minecraft:hand_equipped, minecraft:damage はオブジェクト
    #    {"value": ...} 形式(警告なしで確認済み)。
    for name, cfg in CHAIN_TOOLS.items():
        components = {
            "minecraft:display_name": {"value": f"item.{NAMESPACE}:{name}"},
            "minecraft:max_stack_size": 1,
            "minecraft:hand_equipped": {"value": True},
            "minecraft:durability": {
                "max_durability": DURABILITY,
                "damage_chance": {"min": 0, "max": 100},
            },
            "minecraft:enchantable": {"slot": cfg["enchant_slot"], "value": ENCHANTABILITY},
            "minecraft:repairable": {
                "repair_items": [
                    {"items": ["minecraft:chain"], "repair_amount": "query.max_durability * 0.25"}
                ]
            },
            "minecraft:damage": {"value": max(0, cfg["attack_total"] - 1)},
            "minecraft:tags": {"tags": cfg["tags"]},
        }
        if cfg["digger"]:
            components["minecraft:digger"] = {
                "use_efficiency": True,
                "destroy_speeds": cfg["digger"],
            }
        data = {
            "format_version": ITEM_FORMAT_VERSION,
            "minecraft:item": {
                "description": {
                    "identifier": f"{NAMESPACE}:{name}",
                    "menu_category": {"category": "equipment"},
                },
                "components": components,
            },
        }
        write_json(os.path.join(BP, "items", f"{name}.json"), data)


def write_bp_chain_tool_recipes():
    for name, cfg in CHAIN_TOOLS.items():
        data = {
            "format_version": RECIPE_FORMAT_VERSION,
            "minecraft:recipe_shaped": {
                "description": {"identifier": f"{NAMESPACE}:{name}_recipe"},
                "tags": ["crafting_table"],
                "pattern": cfg["pattern"],
                "key": {
                    "X": {"item": "minecraft:chain"},
                    "#": {"item": "minecraft:stick"},
                },
                "result": {"item": f"{NAMESPACE}:{name}", "count": 1},
            },
        }
        write_json(os.path.join(BP, "recipes", f"{name}.json"), data)


def write_rp_terrain_texture_index():
    data = {
        "resource_pack_name": "springcat_addon",
        "texture_name": "atlas.terrain",
        "padding": 8,
        "num_mip_levels": 4,
        "texture_data": {
            "turbo_block": {"textures": "textures/blocks/turbo_block"},
        },
    }
    write_json(os.path.join(RP, "textures", "terrain_texture.json"), data)


def write_rp_blocks_json():
    data = {
        "format_version": [1, 1, 0],
        f"{NAMESPACE}:turbo_block": {
            "sound": "stone",
        },
    }
    write_json(os.path.join(RP, "blocks.json"), data)


def write_scripts():
    js = '''\
import { world, system } from "@minecraft/server";

/**
 * SpringCat Addon — Spring Turbo Block
 *
 * springcat:turbo_block を敷いた上にレール(通常/パワード/アクティベーター
 * どれでも可)を置くと、その上を通過するトロッコを「毎tick」加速し続ける。
 * バニラのパワードレールは加速目標が 8 block/s でハードキャップされているが、
 * これはエンジン内蔵のレール加速ロジックの上限であり、Script API の
 * applyImpulse で外から与える速度はこの上限の対象外のため、無上限に加速する。
 *
 * 安全のための注意 (README 参照):
 *  - 本当に上限を設けていないため、長い直線ではいずれ 1tick で1ブロック以上
 *    移動するような速度域に達し、当たり判定がすり抜けたり脱線しうる。
 *  - 減速したい場合は turbo_block を敷かない区間を挟めばよい(バニラの
 *    摩擦で自然に減速する)。
 */

const TURBO_BLOCK = "springcat:turbo_block";

// 1tickごとに進行方向へ加算する速度 (block/tick)。20tick=1秒。
const BOOST_PER_TICK = 0.12;
// これ未満の速度では加速しない(静止したトロッコが誤って動き出さないように)。
const MIN_SPEED_TO_BOOST = 0.02;

const MINECART_TYPES = [
  "minecraft:minecart",
  "minecraft:chest_minecart",
  "minecraft:hopper_minecart",
  "minecraft:tnt_minecart",
  "minecraft:command_block_minecart",
];

system.runInterval(() => {
  for (const dimensionId of ["overworld", "nether", "the_end"]) {
    let dimension;
    try {
      dimension = world.getDimension(dimensionId);
    } catch (e) {
      continue;
    }
    for (const type of MINECART_TYPES) {
      let carts;
      try {
        carts = dimension.getEntities({ type });
      } catch (e) {
        continue;
      }
      for (const cart of carts) {
        boostIfOnTurboBlock(dimension, cart);
      }
    }
  }
}, 1);

function boostIfOnTurboBlock(dimension, cart) {
  let velocity;
  try {
    velocity = cart.getVelocity();
  } catch (e) {
    return; // カートが既に無効化されている等
  }

  const horizSpeed = Math.hypot(velocity.x, velocity.z);
  if (horizSpeed < MIN_SPEED_TO_BOOST) return;

  let supportBlock;
  try {
    // レール自体は非ソリッドなので、getBlockBelow はレールを透過して
    // その下の最初のソリッドブロック (= turbo_block) を返す。
    supportBlock = dimension.getBlockBelow(cart.location);
  } catch (e) {
    return;
  }
  if (!supportBlock || supportBlock.typeId !== TURBO_BLOCK) return;

  const dirX = velocity.x / horizSpeed;
  const dirZ = velocity.z / horizSpeed;

  try {
    cart.applyImpulse({ x: dirX * BOOST_PER_TICK, y: 0, z: dirZ * BOOST_PER_TICK });
  } catch (e) {
    // カートが同tick中に破棄された等
  }
}
'''
    with open(os.path.join(BP, "scripts", "main.js"), "w", encoding="utf-8") as f:
        f.write(js)


# ---------------------------------------------------------------------------
# behavior pack: entity + item
# ---------------------------------------------------------------------------

def write_bp_entity():
    data = {
        "format_version": "1.21.0",
        "minecraft:entity": {
            "description": {
                "identifier": f"{NAMESPACE}:spring_cat",
                "is_spawnable": True,
                "is_summonable": True,
                "is_experimental": False,
            },
            "component_groups": {},
            "components": {
                "minecraft:type_family": {"family": ["spring_cat", "mob"]},
                "minecraft:collision_box": {"width": 0.5, "height": 0.7},
                "minecraft:health": {"value": 10, "max": 10},
                "minecraft:movement": {"value": 0.25},
                "minecraft:movement.basic": {},
                "minecraft:navigation.walk": {
                    "can_path_over_water": True,
                    "avoid_water": True,
                    "avoid_damage_blocks": True,
                },
                "minecraft:jump.static": {},
                "minecraft:can_climb": {},
                "minecraft:physics": {},
                "minecraft:pushable": {"is_pushable": True, "is_pushable_by_piston": True},
                "minecraft:despawn": {"despawn_from_distance": {}},
                "minecraft:nameable": {},
                "minecraft:behavior.float": {"priority": 0},
                "minecraft:behavior.panic": {"priority": 1, "speed_multiplier": 1.5},
                "minecraft:behavior.random_stroll": {"priority": 6, "speed_multiplier": 0.8},
                "minecraft:behavior.look_at_player": {
                    "priority": 7, "look_distance": 6.0, "probability": 0.02,
                },
                "minecraft:behavior.random_look_around": {"priority": 8},
                "minecraft:behavior.hurt_by_target": {"priority": 3},
            },
        },
    }
    write_json(os.path.join(BP, "entities", "spring_cat.json"), data)


def write_bp_item():
    # Bedrock Dedicated Server 1.26.44.3 を実際に起動してコンテンツログを
    # 確認し、警告ゼロになるまで検証した結果の形式:
    #  - minecraft:icon は書かない(item_texture.json のキーが識別子の短縮名
    #    と一致していれば自動解決される。書くと逆にスキーマ警告で無視された)
    #  - minecraft:food.effects は format_version "1.16.0" では警告なしで
    #    読み込めた(vanilla の golden_apple.json も同じ仕組みを使っている)。
    #    "1.21.0" にすると "not present in the Schema" 警告が出て無視される
    #    ため、spring_treat だけ低い format_version を使う。
    #  - description.category ではなく description.menu_category.category。
    data = {
        "format_version": FOOD_ITEM_FORMAT_VERSION,
        "minecraft:item": {
            "description": {
                "identifier": f"{NAMESPACE}:spring_treat",
                "menu_category": {"category": "nature"},
            },
            "components": {
                "minecraft:display_name": {"value": f"item.{NAMESPACE}:spring_treat"},
                "minecraft:max_stack_size": 16,
                "minecraft:food": {
                    "nutrition": 2,
                    "can_always_eat": True,
                    "effects": [
                        {"name": "jump_boost", "chance": 1.0, "duration": 15, "amplifier": 1},
                        {"name": "speed", "chance": 1.0, "duration": 15, "amplifier": 0},
                    ],
                },
                "minecraft:use_animation": {"value": "eat"},
                "minecraft:use_modifiers": {"use_duration": 1.2, "movement_modifier": 0.35},
            },
        },
    }
    write_json(os.path.join(BP, "items", "spring_treat.json"), data)


# ---------------------------------------------------------------------------
# resource pack: client entity + item textures + lang
# ---------------------------------------------------------------------------

def write_rp_entity():
    data = {
        "format_version": "1.10.0",
        "minecraft:client_entity": {
            "description": {
                "identifier": f"{NAMESPACE}:spring_cat",
                "materials": {"default": "entity_alphatest"},
                "textures": {"default": "textures/entity/spring_cat"},
                "geometry": {"default": "geometry.spring_cat"},
                "render_controllers": ["controller.render.default"],
                "spawn_egg": {"base_color": "#e8743b", "overlay_color": "#fdf7f0"},
            }
        },
    }
    write_json(os.path.join(RP, "entity", "spring_cat.entity.json"), data)


def write_rp_item_texture_index():
    texture_data = {
        "spring_treat": {"textures": "textures/items/spring_treat"},
    }
    for name in CHAIN_TOOLS:
        texture_data[name] = {"textures": f"textures/items/{name}"}
    data = {
        "resource_pack_name": "springcat_addon",
        "texture_name": "atlas.items",
        "texture_data": texture_data,
    }
    write_json(os.path.join(RP, "textures", "item_texture.json"), data)


def write_lang_files():
    chain_tool_names_en = {
        "chain_pickaxe": "Chain Pickaxe",
        "chain_axe": "Chain Axe",
        "chain_shovel": "Chain Shovel",
        "chain_hoe": "Chain Hoe",
        "chain_sword": "Chain Sword",
    }
    chain_tool_names_ja = {
        "chain_pickaxe": "チェーンのつるはし",
        "chain_axe": "チェーンの斧",
        "chain_shovel": "チェーンのシャベル",
        "chain_hoe": "チェーンのクワ",
        "chain_sword": "チェーンの剣",
    }

    entries = [
        f"entity.{NAMESPACE}:spring_cat.name=Spring Cat",
        f"item.{NAMESPACE}:spring_treat.name=Spring Treat",
        f"tile.{NAMESPACE}:turbo_block.name=Spring Turbo Block",
    ] + [f"item.{NAMESPACE}:{k}.name={v}" for k, v in chain_tool_names_en.items()]
    entries_ja = [
        f"entity.{NAMESPACE}:spring_cat.name=スプリングキャット",
        f"item.{NAMESPACE}:spring_treat.name=スプリングトリート",
        f"tile.{NAMESPACE}:turbo_block.name=スプリングブースターブロック",
    ] + [f"item.{NAMESPACE}:{k}.name={v}" for k, v in chain_tool_names_ja.items()]
    with open(os.path.join(RP, "texts", "en_US.lang"), "w", encoding="utf-8") as f:
        f.write("\n".join(entries) + "\n")
    with open(os.path.join(RP, "texts", "ja_JP.lang"), "w", encoding="utf-8") as f:
        f.write("\n".join(entries_ja) + "\n")
    write_json(os.path.join(RP, "texts", "languages.json"), ["en_US", "ja_JP"])
    with open(os.path.join(BP, "texts", "en_US.lang"), "w", encoding="utf-8") as f:
        f.write("\n")
    write_json(os.path.join(BP, "texts", "languages.json"), ["en_US"])


# ---------------------------------------------------------------------------
# zip packaging
# ---------------------------------------------------------------------------

def zip_dir(src_dir, zip_path):
    if os.path.exists(zip_path):
        os.remove(zip_path)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, _dirs, files in os.walk(src_dir):
            for fn in files:
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, src_dir)
                zf.write(full, rel)


def main():
    reset_out()
    write_manifests()
    draw_pack_icon(os.path.join(BP, "pack_icon.png"))
    draw_pack_icon(os.path.join(RP, "pack_icon.png"))

    build_spring_cat_geo_and_texture()
    build_spring_treat_texture()
    build_turbo_block_texture()
    build_chain_tool_textures()

    write_bp_entity()
    write_bp_item()
    write_bp_turbo_block()
    write_bp_turbo_recipe()
    write_bp_chainmail_recipes()
    write_bp_chain_tools()
    write_bp_chain_tool_recipes()
    write_scripts()
    write_rp_entity()
    write_rp_item_texture_index()
    write_rp_terrain_texture_index()
    write_rp_blocks_json()
    write_lang_files()

    bp_pack = os.path.join(OUT, "SpringCat_BP.mcpack")
    rp_pack = os.path.join(OUT, "SpringCat_RP.mcpack")
    zip_dir(BP, bp_pack)
    zip_dir(RP, rp_pack)

    addon_path = os.path.join(OUT, "SpringCat.mcaddon")
    if os.path.exists(addon_path):
        os.remove(addon_path)
    with zipfile.ZipFile(addon_path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.write(bp_pack, "SpringCat_BP.mcpack")
        zf.write(rp_pack, "SpringCat_RP.mcpack")

    print(f"Built {addon_path} ({os.path.getsize(addon_path)} bytes)")

    store_zip_path = build_store_zip(addon_path)
    print(f"Built {store_zip_path} ({os.path.getsize(store_zip_path)} bytes)")


def build_store_zip(addon_path):
    """ストア/配布サイト提出用の zip。.mcaddon 本体 + 掲載用アイコン + 説明文を同梱する。"""
    version_str = ".".join(str(v) for v in ADDON_VERSION)
    store_zip_path = os.path.join(OUT, f"SpringCat-Addon-v{version_str}.zip")
    if os.path.exists(store_zip_path):
        os.remove(store_zip_path)

    description = f"""SpringCat Addon v{version_str}
springcat.ragdollp.site 公式 Minecraft 統合版アドオン

【収録コンテンツ】
- Spring Cat: 相棒モブ(スポーンエッグ対応、/summon springcat:spring_cat)
- Spring Treat: 食べるとジャンプ力・スピードが上がるアイテム
- Spring Turbo Block: レールの下に敷くと、通過するトロッコを上限なく加速し続けるブロック
  (金ブロック + Spring Treat でクラフト可能)
- Chain 装備一式: バニラの chain(鎖)ブロックからチェーンメイル一式(ヘルメット/
  チェストプレート/レギンス/ブーツ)を実際にクラフト可能に(通常は非クラフト)
- Chain 道具一式: つるはし/斧/シャベル/クワ/剣。ステータスは Minecraft 公式の
  銅(Copper)ツールと同等(耐久191・採掘効率5・エンチャント率13、攻撃力/採掘レベルも準拠)
  素材は chain。修理も chain で可能。

【インストール方法】
同梱の SpringCat.mcaddon を端末に転送してタップすると、Minecraft のインポート
確認画面が開きます。インポート後、ワールド設定の「アドオン」で Behavior /
Resource 両方を有効化してください(Spring Turbo Block はスクリプト API を
使うため、ワールドの実験的機能の有効化が必要な場合があります)。

【対応バージョン】
min_engine_version {'.'.join(str(v) for v in MIN_ENGINE)} 以降

【配布元】
https://springcat.ragdollp.site/
"""

    with zipfile.ZipFile(store_zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.write(addon_path, "SpringCat.mcaddon")
        zf.write(os.path.join(BP, "pack_icon.png"), "icon.png")
        zf.writestr("description.txt", description)

    return store_zip_path


if __name__ == "__main__":
    main()
