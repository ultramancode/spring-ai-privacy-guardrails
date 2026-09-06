#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -lt 2 || "$#" -gt 3 ]]; then
  echo "Usage: $0 <release-version> <bom.json> [--allow-noncanonical-source]" >&2
  exit 2
fi

release_version="$1"
bom_file="$2"
allow_noncanonical_source=false
if [[ "${3:-}" == "--allow-noncanonical-source" ]]; then
  allow_noncanonical_source=true
elif [[ "$#" -eq 3 ]]; then
  echo "Unknown option: $3" >&2
  exit 2
fi
public_vcs="https://github.com/ultramancode/spring-ai-privacy-guardrails"
module_prefix="spring-ai-privacy-guardrails-"
private_repository_identity="${module_prefix}private"

if [[ ! -f "$bom_file" ]]; then
  echo "Release SBOM is missing: $bom_file" >&2
  exit 1
fi

if [[ "$allow_noncanonical_source" != true ]] \
    && grep -qi "$private_repository_identity" "$bom_file"; then
  echo "Release SBOM contains the private repository identity" >&2
  exit 1
fi

jq -e \
  --arg version "$release_version" \
  --arg public_vcs "$public_vcs" \
  --arg module_prefix "$module_prefix" \
  '
    (.metadata.component.version == $version)
    and any(.metadata.component.licenses[]?; .license.id == "Apache-2.0")
    and any(.metadata.component.externalReferences[]?;
      .type == "vcs" and .url == $public_vcs)
    and (
      def expected_modules: [
        "spring-ai-privacy-guardrails-core",
        "spring-ai-privacy-guardrails-opennlp",
        "spring-ai-privacy-guardrails-opennlp-spring-boot-starter",
        "spring-ai-privacy-guardrails-presidio",
        "spring-ai-privacy-guardrails-presidio-spring-boot-starter",
        "spring-ai-privacy-guardrails-spring-ai",
        "spring-ai-privacy-guardrails-spring-boot-starter",
        "spring-ai-privacy-guardrails-spring-security",
        "spring-ai-privacy-guardrails-spring-security-spring-boot-starter",
        "spring-ai-privacy-guardrails-test"
      ];
      [.components[]
        | select(
            .group == "io.github.ultramancode"
            and (.name | startswith($module_prefix))
          )
      ] as $modules
      | ($modules | map(.name) | sort) == (expected_modules | sort)
      and all($modules[];
        . as $module
        | $module.version == $version
        and ($module.purl | startswith(
          "pkg:maven/io.github.ultramancode/" + $module.name + "@" + $version
        ))
        and any($module.licenses[]?; .license.id == "Apache-2.0")
        and any($module.externalReferences[]?;
          .type == "vcs" and .url == $public_vcs)
      )
    )
  ' "$bom_file" >/dev/null

echo "Verified release SBOM identity, version, licenses, VCS, and expected module purls."
