const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, 'src/main/assets/zadix');
const files = new Map([
  ['/', ['index.html', 'text/html']],
  ['/index.html', ['index.html', 'text/html']],
  ['/style.css', ['style.css', 'text/css']],
  ['/app.js', ['app.js', 'text/javascript']],
  ['/rules.js', ['rules.js', 'text/javascript']],
  ['/release.json', ['release.json', 'application/json']],
]);
const port = Number(process.argv[2] || '4173');
const server = http.createServer((request, response) => {
  const entry = files.get(new URL(request.url, 'http://127.0.0.1').pathname);
  if (!entry || !['GET','HEAD'].includes(request.method)) {
    response.writeHead(404).end();
    return;
  }
  response.writeHead(200, {'Content-Type': entry[1] + '; charset=utf-8', 'Cache-Control': 'no-store'});
  if (request.method === 'HEAD') response.end();
  else fs.createReadStream(path.join(root, entry[0])).pipe(response);
});
server.listen(port, '127.0.0.1', () => console.log('Zadix: http://127.0.0.1:' + port));
