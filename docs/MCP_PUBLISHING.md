# Publishing to the Marketplace

How to package and publish an MCP server listing so it can be discovered and
installed by other users.

## Listing schema

A listing is validated locally by `validateListing` (MarketplaceListingValidator)
before it is submitted. The rules:

| Field        | Rule                                                                 |
|--------------|----------------------------------------------------------------------|
| name         | at least 3 characters                                                |
| category     | required (one of the marketplace categories)                         |
| description  | at least 20 characters                                               |
| version      | numeric dotted semver, e.g. `1.2.0` (pre-release suffixes allowed)    |
| artifactUrl  | required, must be `http(s)`                                          |
| sha256       | required when `artifactUrl` is set; 64 hex characters of the artifact |
| tags         | at most 10; each 1-32 chars of letters, digits, `.`, `_`, `-`        |

Installs verify the artifact's SHA-256 against the listing before unpacking,
so compute the hash over the exact bytes you upload.

## MCP server packaging

A server installed from the marketplace must speak newline-delimited
JSON-RPC on stdin/stdout (the MCP stdio transport) and answer the
`initialize` handshake the app sends on start. Servers that never answer the
handshake fail honestly instead of hanging discovery.

Custom servers run on-device with the app's privileges. Secrets (API keys,
tokens) must never be baked into the package or the manifest `env` — the app
stores them in its encrypted credential store and injects them at launch.

## Submitting

Use the Marketplace "Publish" flow in the app; drafts that fail validation
are refused locally with the list of problems above. A listing is only
visible to others once the registry accepts it — items that fail to submit
stay local-only.
