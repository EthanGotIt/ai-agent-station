#!/usr/bin/env node

import { existsSync } from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

export const DEFAULT_REGISTRIES = Object.freeze([
  "https://registry.npmmirror.com/",
  "https://registry.npmjs.org/",
]);

const SCRIPT_PATH = fileURLToPath(import.meta.url);
const REPOSITORY_ROOT = path.resolve(path.dirname(SCRIPT_PATH), "..");

/**
 * 规范化 registry 地址，避免把凭据或不支持的协议传给 npm。
 */
export function normalizeRegistry(value) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error("registry 地址不能为空");
  }

  let url;
  try {
    url = new URL(value.trim());
  } catch {
    throw new Error("registry 地址无效");
  }

  if (url.protocol !== "https:" && url.protocol !== "http:") {
    throw new Error("registry 只支持 HTTP(S)");
  }
  if (url.username || url.password) {
    throw new Error("registry 地址不得包含用户名或密码");
  }
  if (url.search || url.hash) {
    throw new Error("registry 地址不得包含 query 或 hash");
  }

  if (!url.pathname.endsWith("/")) {
    url.pathname += "/";
  }
  return url.toString();
}

/**
 * 解析逗号/换行分隔的 registry 列表，并删除重复地址。
 */
export function resolveRegistries(value = process.env.NPM_REGISTRIES) {
  const candidates = Array.isArray(value)
    ? value
    : typeof value === "string" && value.trim() !== ""
      ? value.split(/[\s,]+/)
      : DEFAULT_REGISTRIES;
  const registries = [];

  for (const candidate of candidates) {
    const registry = normalizeRegistry(candidate);
    if (!registries.includes(registry)) {
      registries.push(registry);
    }
  }

  if (registries.length === 0) {
    throw new Error("至少需要一个 registry 地址");
  }
  return registries;
}

export function buildNpmCiArgs(registry, extraArgs = []) {
  return [
    "ci",
    `--registry=${registry}`,
    "--replace-registry-host=always",
    "--no-audit",
    "--no-fund",
    ...extraArgs,
  ];
}

/**
 * 依次尝试 registry；失败时切换到下一个，成功后立即停止。
 */
export function runInstall({
  installDirectory,
  registries,
  extraArgs = [],
  spawnSyncImpl = spawnSync,
  log = console.log,
  warn = console.warn,
}) {
  if (!installDirectory) {
    throw new Error("installDirectory 不能为空");
  }
  if (!Array.isArray(registries) || registries.length === 0) {
    throw new Error("至少需要一个 registry 地址");
  }

  const npmCommand = process.platform === "win32"
    ? process.env.ComSpec ?? "cmd.exe"
    : "npm";
  const spawnOptions = {
    cwd: installDirectory,
    stdio: "inherit",
  };
  const failures = [];

  for (const [index, registry] of registries.entries()) {
    log(`[npm-ci-fallback] 尝试 ${index + 1}/${registries.length}: ${registry}`);
    try {
      const npmArgs = buildNpmCiArgs(registry, extraArgs);
      const commandArgs = process.platform === "win32"
        ? ["/d", "/s", "/c", "npm.cmd", ...npmArgs]
        : npmArgs;
      const result = spawnSyncImpl(
        npmCommand,
        commandArgs,
        spawnOptions,
      );
      if (result?.status === 0) {
        log(`[npm-ci-fallback] 成功: ${registry}`);
        return { code: 0, registry, failures };
      }

      const reason = result?.error?.message
        ?? `npm 退出码 ${result?.status ?? "unknown"}${result?.signal ? ` (${result.signal})` : ""}`;
      failures.push({ registry, reason });
      warn(`[npm-ci-fallback] 失败: ${registry}，${reason}`);
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      failures.push({ registry, reason });
      warn(`[npm-ci-fallback] 失败: ${registry}，${reason}`);
    }
  }

  warn(`[npm-ci-fallback] 所有 registry 均失败（${failures.length} 次）`);
  return { code: 1, failures };
}

function resolveInstallDirectory(prefix) {
  if (prefix) {
    return path.resolve(process.cwd(), prefix);
  }
  if (existsSync(path.join(process.cwd(), "package.json"))) {
    return process.cwd();
  }
  return path.join(REPOSITORY_ROOT, "agent-fronted");
}

function parseArguments(argv) {
  let prefix;
  let dryRun = false;
  const registryArgs = [];
  const extraArgs = [];
  let passThrough = false;

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (passThrough) {
      extraArgs.push(argument);
    } else if (argument === "--") {
      passThrough = true;
    } else if (argument === "--dry-run") {
      dryRun = true;
    } else if (argument === "--prefix") {
      prefix = argv[++index];
      if (!prefix) {
        throw new Error("--prefix 必须提供目录");
      }
    } else if (argument === "--registry") {
      const registry = argv[++index];
      if (!registry) {
        throw new Error("--registry 必须提供地址");
      }
      registryArgs.push(registry);
    } else {
      throw new Error(`未知参数: ${argument}`);
    }
  }

  return {
    installDirectory: resolveInstallDirectory(prefix),
    registries: resolveRegistries(registryArgs.length > 0 ? registryArgs : undefined),
    extraArgs,
    dryRun,
  };
}

export function main(argv = process.argv.slice(2)) {
  try {
    const options = parseArguments(argv);
    if (options.dryRun) {
      for (const registry of options.registries) {
        console.log(
          `[npm-ci-fallback] dry-run: npm ${buildNpmCiArgs(registry, options.extraArgs).join(" ")}`,
        );
      }
      return 0;
    }

    return runInstall(options).code;
  } catch (error) {
    console.error(`[npm-ci-fallback] ${error instanceof Error ? error.message : String(error)}`);
    return 2;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(SCRIPT_PATH)) {
  process.exitCode = main();
}
