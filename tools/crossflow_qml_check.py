import os
import re
import sys

SKIN = os.path.join("res", "qml", "Skins", "CrossFlow")


def strip_non_code(source):
    out = []
    index = 0
    length = len(source)
    state = "code"
    while index < length:
        char = source[index]
        following = source[index + 1] if index + 1 < length else ""
        if state == "code":
            if char == '"' or char == "'":
                state = char
                out.append(" ")
            elif char == "`":
                state = "`"
                out.append(" ")
            elif char == "/" and following == "/":
                state = "line"
                out.append("  ")
                index += 1
            elif char == "/" and following == "*":
                state = "block"
                out.append("  ")
                index += 1
            else:
                out.append(char)
        elif state in ('"', "'", "`"):
            if char == "\\":
                out.append("  ")
                index += 1
            elif char == state:
                state = "code"
                out.append(" ")
            elif char == "\n":
                out.append("\n")
                if state != "`":
                    state = "code"
            else:
                out.append(" ")
        elif state == "line":
            if char == "\n":
                state = "code"
                out.append("\n")
            else:
                out.append(" ")
        elif state == "block":
            if char == "*" and following == "/":
                state = "code"
                out.append("  ")
                index += 1
            elif char == "\n":
                out.append("\n")
            else:
                out.append(" ")
        index += 1
    return "".join(out)


def main():
    problems = []
    if not os.path.isdir(SKIN):
        return ["missing skin directory: " + SKIN]
    for name in ("MainWindow.qml", "CrossFlowDeck.qml", "CrossFlowMixView.qml"):
        if not os.path.isfile(os.path.join(SKIN, name)):
            problems.append("missing required file: " + name)
    for path in sorted(os.listdir(SKIN)):
        if not path.endswith(".qml"):
            continue
        full = os.path.join(SKIN, path)
        with open(full, encoding="utf-8") as handle:
            source = handle.read()
        code = strip_non_code(source)
        pairs = {")": "(", "]": "[", "}": "{"}
        stack = []
        for char in code:
            if char in "{([":
                stack.append(char)
            elif char in "})]":
                if not stack or stack[-1] != pairs[char]:
                    problems.append("%s: unbalanced delimiters" % path)
                    break
                stack.pop()
        if stack:
            problems.append("%s: unclosed delimiters" % path)
        for asset in re.findall(r'Qt\.resolvedUrl\("([^"]+)"\)', source):
            if not os.path.exists(os.path.join(SKIN, asset)):
                problems.append("%s: missing asset %s" % (path, asset))
        for component in re.findall(r"\bCrossFlow\.([A-Za-z0-9_]+)", source):
            if not os.path.exists(os.path.join(SKIN, component + ".qml")):
                problems.append("%s: unknown component %s" % (path, component))
        for import_path in re.findall(r'import\s+"([^"]+)"', source):
            if not os.path.exists(os.path.normpath(os.path.join(SKIN, import_path))):
                problems.append("%s: bad import %s" % (path, import_path))
        for block in re.findall(r"Mixxx\.ControlProxy\s*\{(.*?)\n\s*\}", source, flags=re.S):
            if "key:" not in block or "group:" not in block:
                problems.append("%s: ControlProxy without group/key" % path)
    return problems


if __name__ == "__main__":
    issues = main()
    for issue in issues:
        print("ERROR: " + issue)
    if issues:
        sys.exit(1)
    print("CrossFlow QML structure OK")
