FROM node:20-alpine

RUN addgroup -S screenlink && adduser -S screenlink -G screenlink
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY server.js manage-devices.js ./

# devices.json is admin-managed state, not baked into the image - it is
# expected to be bind-mounted in (see deploy/docker-compose.yml).
RUN mkdir -p /data && chown screenlink:screenlink /data
ENV DEVICES_FILE=/data/devices.json
ENV PORT=8787

USER screenlink
EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
  CMD node -e "require('net').createConnection({port: process.env.PORT||8787, host:'127.0.0.1'}).on('connect', function(){this.end();process.exit(0)}).on('error', () => process.exit(1))"

CMD ["node", "server.js"]
