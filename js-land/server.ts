import path from "path";
import fs from "fs";

const server = Bun.serve({
  port: 8090,
  fetch(req) {
    const url = new URL(req.url);
    let requestedPath = path
      .normalize(url.pathname)
      .replace(/^(\.\.[\/\\])+/, "");

    if (requestedPath === "/") {
      requestedPath = "/index.html";
    }

    const filePath = path.join(process.cwd(), "public", requestedPath);

    if (!filePath.startsWith(path.join(process.cwd(), "public"))) {
      return new Response("Forbidden", { status: 403 });
    }

    if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
      const headers = new Headers({
        "Cross-Origin-Opener-Policy": "same-origin",
        "Cross-Origin-Embedder-Policy": "require-corp",
      });

      const file = Bun.file(filePath);
      return new Response(file, { headers });
    }

    return new Response("Not Found", { status: 404 });
  },
});

console.log(`Bun server running on http://localhost:${server.port}`);
