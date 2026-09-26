// SPDX-License-Identifier: Apache-2.0
// Stands in for the Amazon Personalize Events endpoint. The real AWS SDK in the
// Event API sends genuine signed PutEvents requests here; we record their bodies.
import { createServer } from "node:http";
import { writeFileSync } from "node:fs";

const OUT = process.argv[2];
const captured = [];

createServer((req, res) => {
  let body = "";
  req.on("data", (chunk) => (body += chunk));
  req.on("end", () => {
    captured.push({
      method: req.method,
      path: req.url,
      signed: String(req.headers.authorization ?? "").startsWith("AWS4-HMAC-SHA256"),
      body: body ? JSON.parse(body) : null,
    });
    writeFileSync(OUT, JSON.stringify(captured, null, 2));
    res.writeHead(200, { "Content-Type": "application/x-amz-json-1.1" });
    res.end("{}");
  });
}).listen(4566, () => console.log("capture server on :4566"));
