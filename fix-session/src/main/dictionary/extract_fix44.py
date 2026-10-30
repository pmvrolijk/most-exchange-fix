#!/usr/bin/env python3
"""
Writes the adapter's FIX dictionary (Design.md §5.4) in the QuickFIX format Artio's
CodecGenerationTool reads, from the FIX Trading Community's FIX 4.4 repository.

    python3 -I extract_fix44.py <FixRepository44.xml> fix44-most.xml

The source is FixRepository44.xml from io.fixprotocol.orchestrations:fix-standard:1.5.3 on Maven
Central (jar SHA-1 fc615731cbef7fdc0c0e3642d6bed910b402a9b1), Apache-2.0, (c) FIX Protocol Ltd.
See LICENSE-fix-orchestrations.txt beside this file.

What it keeps (decided 2026-10-20, Design.md §5.4):
- the header, the trailer and the session messages, as FIX 4.4 defines them;
- only the application messages the adapter handles, each with its complete FIX 4.4 structure,
  so that a standard field the adapter does not read is accepted and ignored (§5.1);
- only the fields, components and code sets those reach.
Then it adds the adapter's own tags (ADDITIONS below), and cuts the code sets in CODE_SET_CUTS to
the values the adapter serves. Nothing else differs from FIX 4.4.

Run it again, never edit its output, when the set of messages or the additions change.
"""

import re
import sys
import xml.etree.ElementTree as ET
from xml.sax.saxutils import quoteattr

NS = {"fixr": "http://fixprotocol.io/2020/orchestra/repository"}

# Message types the adapter handles. Session messages first; Artio's FixDictionaryImpl needs all
# seven and BusinessMessageReject (35=j).
MESSAGES = [
    "0", "1", "2", "3", "4", "5", "A",  # session
    "j",                                # BusinessMessageReject: what the adapter answers an unsupported type with
    "D", "F", "G", "H", "AF",           # order entry in (§3.3; 35=G only to be refused)
    "8", "9",                           # order entry out (§5.3)
    "x", "V",                           # market data in: SecurityListRequest, MarketDataRequest (§4)
    "y", "W", "X", "Y",                 # market data out: SecurityList, snapshot, incremental, MarketDataRequestReject (§4)
]

# The adapter's own tags (Design.md §5.4).
ADDITIONAL_FIELDS = [
    # FIX Latest EP211: String. The adapter parses it as a positive int64 (§5.1).
    (2362, "SelfMatchPreventionID", "STRING", []),
    # FIX Latest EP280 defines 1, 2 and 3. Only those the exchange carries are in the dictionary, so a
    # 3 fails the decoder's validation and is answered with a 35=3, SessionRejectReason 5 (decided
    # 2026-10-20, §5.1).
    (2964, "SelfMatchPreventionInstruction", "INT", [("1", "CANCEL_AGGRESSIVE"), ("2", "CANCEL_PASSIVE")]),
    # User-defined (decided 2026-10-20): Y only on an order whose fate is unknown (§3.4).
    (5001, "OrdStatusUnknown", "BOOLEAN", []),
    # FIX 5.0's MinPriceIncrement, Float: the tick, on each 35=y entry (§4). FIX 4.4 has no tag for it.
    (969, "MinPriceIncrement", "FLOAT", []),
]
ADDITIONS_BY_MSG_TYPE = {
    "D": [(2362, False), (2964, False)],
    "8": [(2362, False), (2964, False), (5001, False)],
    "9": [(5001, False)],
}
# The same inside a repeating group, by the group's component name (§4).
ADDITIONS_BY_GROUP = {
    "SecListGrp": [(969, False)],  # 35=y's NoRelatedSym
}

# FIX 4.4 code sets cut to what the adapter serves, so that decoder validation answers any other value
# with a 35=3, SessionRejectReason 5, as for 2964 above.
CODE_SET_CUTS = {
    # MassStatusReqType: 1 one security, 7 all orders (decided 2026-10-27, Design.md §3.3).
    585: {"1", "7"},
}

HEADER_ID = "1024"
TRAILER_ID = "1025"


def upper_snake(name):
    """CamelCase code name -> the UPPER_SNAKE description QuickFIX dictionaries use, which Artio turns into enum constants."""
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", s)
    return s.upper()


class Repository:
    def __init__(self, path):
        root = ET.parse(path).getroot()
        if root.get("version") != "FIX.4.4":
            sys.exit(f"not a FIX 4.4 repository: {root.get('version')}")
        self.code_sets = {}
        for cs in root.iterfind("fixr:codeSets/fixr:codeSet", NS):
            codes = [(c.get("value"), c.get("name")) for c in cs.iterfind("fixr:code", NS)]
            self.code_sets[cs.get("name")] = (cs.get("type"), codes)
        self.fields = {}
        for f in root.iterfind("fixr:fields/fixr:field", NS):
            self.fields[f.get("id")] = (f.get("name"), f.get("type"))
        self.components = {c.get("id"): c for c in root.iterfind("fixr:components/fixr:component", NS)}
        self.groups = {g.get("id"): g for g in root.iterfind("fixr:groups/fixr:group", NS)}
        self.messages = {m.get("msgType"): m for m in root.iterfind("fixr:messages/fixr:message", NS)}

    @staticmethod
    def items(element):
        """(kind, id, required) of an aggregate's members, in order; numInGroup excluded."""
        for child in element:
            tag = child.tag.split("}")[1]
            if tag in ("fieldRef", "componentRef", "groupRef"):
                yield tag, child.get("id"), child.get("presence") == "required"
            elif tag == "structure":
                yield from Repository.items(child)


class Writer:
    def __init__(self, repo):
        self.repo = repo
        self.used_fields = set()
        self.used_components = {}  # name -> ("component" | "group", id), in first-reached order

    def field_name(self, field_id):
        self.used_fields.add(field_id)
        return self.repo.fields[field_id][0]

    def members(self, element, indent, inline_groups):
        out = []
        pad = " " * indent
        for kind, ref, required in Repository.items(element):
            req = "Y" if required else "N"
            if kind == "fieldRef":
                out.append(f'{pad}<field name="{self.field_name(ref)}" required="{req}"/>')
            elif kind == "componentRef":
                if ref in (HEADER_ID, TRAILER_ID):
                    continue
                name = self.repo.components[ref].get("name")
                self.used_components.setdefault(name, ("component", ref))
                out.append(f'{pad}<component name="{name}" required="{req}"/>')
            elif inline_groups:
                out.extend(self.group(ref, indent, req))
            else:
                # A group is wrapped in a component of its own name, as QuickFIX's FIX44.xml does, so
                # that Artio generates one decoder for it, shared by every message that carries it.
                name = self.repo.groups[ref].get("name")
                self.used_components.setdefault(name, ("group", ref))
                out.append(f'{pad}<component name="{name}" required="{req}"/>')
        return out

    def group(self, group_id, indent, req):
        group = self.repo.groups[group_id]
        count = self.field_name(group.find("fixr:numInGroup", NS).get("id"))
        pad = " " * indent
        added = [f'{pad}  <field name="{additional_name(number)}" required="{"Y" if required else "N"}"/>'
                 for number, required in ADDITIONS_BY_GROUP.get(group.get("name"), [])]
        return ([f'{pad}<group name="{count}" required="{req}">']
                + self.members(group, indent + 2, inline_groups=False)
                + added
                + [f"{pad}</group>"])

    def write(self):
        repo = self.repo
        lines = []
        lines.append("  <header>")
        lines.extend(self.members(repo.components[HEADER_ID], 4, inline_groups=True))
        lines.append("  </header>")
        lines.append("  <trailer>")
        lines.extend(self.members(repo.components[TRAILER_ID], 4, inline_groups=True))
        lines.append("  </trailer>")

        for number, name, _, _ in ADDITIONAL_FIELDS:
            if str(number) in repo.fields:
                sys.exit(f"tag {number} is already FIX 4.4's {repo.fields[str(number)][0]}")

        lines.append("  <messages>")
        for msg_type in MESSAGES:
            message = repo.messages[msg_type]
            category = "admin" if message.get("category") == "Session" else "app"
            lines.append(f'    <message name="{message.get("name")}" msgtype="{msg_type}" msgcat="{category}">')
            lines.extend(self.members(message, 6, inline_groups=False))
            for number, required in ADDITIONS_BY_MSG_TYPE.get(msg_type, []):
                name = additional_name(number)
                lines.append(f'      <field name="{name}" required="{"Y" if required else "N"}"/>')
            lines.append("    </message>")
        lines.append("  </messages>")

        # Components reach further components and groups; resolve until nothing new is reached.
        lines.append("  <components>")
        written = set()
        while len(written) < len(self.used_components):
            for name, (kind, ref) in list(self.used_components.items()):
                if name in written:
                    continue
                written.add(name)
                lines.append(f'    <component name="{name}">')
                if kind == "component":
                    lines.extend(self.members(repo.components[ref], 6, inline_groups=False))
                else:
                    lines.extend(self.group(ref, 6, "N"))
                lines.append("    </component>")
        lines.append("  </components>")

        lines.append("  <fields>")
        fields = [(int(i), *repo.fields[i]) for i in self.used_fields]
        for number, name, type_name in sorted(fields):
            values = []
            if type_name in repo.code_sets:
                type_name, codes = repo.code_sets[type_name]
                values = [(v, upper_snake(n)) for v, n in codes if number not in CODE_SET_CUTS or v in CODE_SET_CUTS[number]]
            lines.extend(field_lines(number, name, type_name.upper(), values))
        for number, name, type_name, values in ADDITIONAL_FIELDS:
            lines.extend(field_lines(number, name, type_name, values))
        lines.append("  </fields>")
        return lines


def additional_name(number):
    return next(n for t, n, _, _ in ADDITIONAL_FIELDS if t == number)


def field_lines(number, name, type_name, values):
    if not values:
        return [f'    <field number="{number}" name="{name}" type="{type_name}"/>']
    return ([f'    <field number="{number}" name="{name}" type="{type_name}">']
            + [f"      <value enum={quoteattr(v)} description={quoteattr(d)}/>" for v, d in values]
            + ["    </field>"])


HEADER_COMMENT = """<!--
  The most-exchange FIX adapter's dictionary: FIX 4.4, the message types the adapter handles, and its
  own tags 2362, 2964, 5001 and 969 (docs/Design.md §4, §5.4).

  GENERATED by extract_fix44.py from FixRepository44.xml in
  io.fixprotocol.orchestrations:fix-standard:1.5.3 (Apache-2.0, (c) FIX Protocol Ltd; see
  LICENSE-fix-orchestrations.txt). Do not edit: change the script and run it again.
-->"""


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    repo = Repository(sys.argv[1])
    body = Writer(repo).write()
    with open(sys.argv[2], "w", encoding="utf-8") as out:
        out.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        out.write(HEADER_COMMENT + "\n")
        out.write('<fix major="4" minor="4" type="FIX">\n')
        out.write("\n".join(body) + "\n")
        out.write("</fix>\n")


if __name__ == "__main__":
    main()
