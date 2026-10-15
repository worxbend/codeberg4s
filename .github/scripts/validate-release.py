#!/usr/bin/env python3
"""Require the selected release tag and every published coordinate to agree."""

import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ARTIFACTS = {'modules.domain': 'codeberg4s-domain_3', 'modules.core': 'codeberg4s-core_3', 'modules.codec': 'codeberg4s-codec_3', 'modules.transport': 'codeberg4s-transport_3', 'modules.client': 'codeberg4s-client_3'}
SELECTOR = 'modules.{domain,core,codec,transport,client}'


def output(*args: str) -> str:
    return subprocess.check_output(args, cwd=ROOT, text=True, timeout=900).strip()


def main() -> None:
    tag = os.environ.get("GITHUB_REF_NAME", "")
    if os.environ.get("GITHUB_REF_TYPE") != "tag" or not re.fullmatch(
        r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", tag
    ) or tag.endswith("-SNAPSHOT"):
        raise ValueError("select a release tag vX.Y.Z; branches and snapshots cannot be published")
    actual = output("git", "rev-parse", "HEAD")
    tagged = output("git", "rev-parse", f"refs/tags/{tag}^{{commit}}")
    validated = os.environ.get("VALIDATED_SHA", "")
    if actual != tagged or actual != os.environ.get("GITHUB_SHA") or actual != validated:
        raise ValueError("checkout, selected tag, workflow SHA and validated CI commit must agree")
    versions = json.loads(output(str(ROOT / "mill"), "show", f"{SELECTOR}.publishVersion"))
    expected = {f"{module}.publishVersion": tag[1:] for module in ARTIFACTS}
    if versions != expected:
        raise ValueError(f"all library versions must match {tag}: {versions}")
    poms = json.loads(output(str(ROOT / "mill"), "show", f"{SELECTOR}.pom"))
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    for module, artifact in ARTIFACTS.items():
        path = poms[f"{module}.pom"].split(":", 3)[3]
        pom = ET.parse(path).getroot()
        coordinates = tuple(pom.findtext(f"m:{field}", namespaces=ns) for field in
                            ("groupId", "artifactId", "version"))
        if coordinates != ("com.worxbend", artifact, tag[1:]):
            raise ValueError(f"unexpected release coordinates: {coordinates}")
        for dependency in pom.findall("m:dependencies/m:dependency", ns):
            dep_id = dependency.findtext("m:artifactId", namespaces=ns)
            if dep_id in ARTIFACTS.values():
                group = dependency.findtext("m:groupId", namespaces=ns)
                version = dependency.findtext("m:version", namespaces=ns)
                if (group, version) != ("com.worxbend", tag[1:]):
                    raise ValueError(f"incorrect internal dependency in {artifact}: {dep_id}")
        print(":".join(coordinates), flush=True)


if __name__ == "__main__":
    main()
