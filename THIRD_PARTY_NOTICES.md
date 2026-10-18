# Third-party notices

Gomoku itself is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
The bundled libraries below are compatible with AGPL-3.0.

Gomoku bundles the following libraries into the release jar:

## Gson

- Project: https://github.com/google/gson
- License: Apache License 2.0

## SQLite JDBC

- Project: https://github.com/xerial/sqlite-jdbc
- License: Apache License 2.0
- The jar also contains the native SQLite binaries published by the SQLite project
  (public domain).

## SLF4J API

- Project: https://www.slf4j.org/
- License: MIT License

## Paper API / PlaceholderAPI

These are `compileOnly` dependencies and are **not** bundled into `Gomoku.jar`.
They are provided by the server (Paper) and the optional PlaceholderAPI plugin.
Their respective licenses apply.

## Not included

The repository does not include the test server (`server/`), world data, local
build outputs, or the unrelated `Raven-main` source tree. Those paths are
excluded via `.gitignore`.
