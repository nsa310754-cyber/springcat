# springcat

A minimal, dependency-free Node.js HTTP server built with the built-in `http` module.

## Requirements

- Node.js >= 18

## Run

```bash
npm start
```

The server listens on `http://0.0.0.0:3000` by default. Override with env vars:

```bash
PORT=8080 HOST=127.0.0.1 npm start
```

For development with auto-reload:

```bash
npm run dev
```

## Endpoints

| Method | Path        | Description                          |
| ------ | ----------- | ------------------------------------ |
| GET    | `/`         | Welcome message and endpoint listing |
| GET    | `/health`   | Health check with uptime             |
| GET    | `/api/time` | Current server time (ISO 8601)       |

Unknown routes return `404` with a JSON error body.

## Test

```bash
npm test
```
