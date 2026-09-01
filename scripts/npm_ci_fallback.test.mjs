import assert from "node:assert/strict";
import test from "node:test";

import {
  buildNpmCiArgs,
  normalizeRegistry,
  resolveRegistries,
  runInstall,
} from "./npm_ci_fallback.mjs";

test("registry 列表规范化并去重", () => {
  assert.deepEqual(
    resolveRegistries("https://registry.npmmirror.com, https://registry.npmjs.org/ https://registry.npmjs.org"),
    ["https://registry.npmmirror.com/", "https://registry.npmjs.org/"],
  );
  assert.throws(() => normalizeRegistry("ftp://registry.example.com"), /只支持 HTTP/);
});

test("registry 校验失败时不回显敏感 URL", () => {
  assert.throws(
    () => normalizeRegistry("https://registry.example.com/?token=secret-value"),
    (error) => {
      assert.match(error.message, /query 或 hash/);
      assert.doesNotMatch(error.message, /secret-value/);
      return true;
    },
  );
});

test("npm ci 参数按当前 registry 重写 lockfile host", () => {
  assert.deepEqual(buildNpmCiArgs("https://registry.npmjs.org/"), [
    "ci",
    "--registry=https://registry.npmjs.org/",
    "--replace-registry-host=always",
    "--no-audit",
    "--no-fund",
  ]);
});

test("第一个 registry 失败时切换到第二个并在成功后停止", () => {
  const calls = [];
  const statuses = [1, 0];
  const result = runInstall({
    installDirectory: ".",
    registries: ["https://registry.npmmirror.com/", "https://registry.npmjs.org/"],
    spawnSyncImpl: (command, args, options) => {
      calls.push({ command, args, options });
      return { status: statuses.shift() };
    },
    log: () => {},
    warn: () => {},
  });

  assert.equal(result.code, 0);
  assert.equal(result.registry, "https://registry.npmjs.org/");
  assert.equal(calls.length, 2);
  assert.ok(calls[0].args.includes("--registry=https://registry.npmmirror.com/"));
  assert.ok(calls[1].args.includes("--registry=https://registry.npmjs.org/"));
});

test("所有 registry 失败时返回失败码", () => {
  const result = runInstall({
    installDirectory: ".",
    registries: ["https://registry.npmmirror.com/", "https://registry.npmjs.org/"],
    spawnSyncImpl: () => ({ status: 1 }),
    log: () => {},
    warn: () => {},
  });

  assert.equal(result.code, 1);
  assert.equal(result.failures.length, 2);
});
