/**
 * 点子铺小程序 CI 脚本：上传体验版 / 生成预览二维码
 *
 * 用法:
 *   npm run upload    上传代码，生成体验版（微信后台可见）
 *   npm run preview   生成开发版预览二维码（输出 preview-qrcode.jpg，真机扫码验证）
 *
 * 前置条件（二选一）:
 *   1. 密钥文件：MP 平台 → 开发管理 → 开发设置 → 小程序代码上传 → 下载密钥，
 *      保存为 keys/private.<appid>.key（keys/ 已在 .gitignore 中，不会入库）
 *   2. 环境变量：WX_APPID + WX_PRIVATE_KEY_PATH
 *
 * 注意：MP 平台需将本机出口 IP 加入"小程序代码上传密钥"的 IP 白名单，
 *       否则上传会报 61024 (ip not in whitelist)。
 */
const ci = require('miniprogram-ci');
const fs = require('fs');
const path = require('path');
const pkg = require('../package.json');

const ROOT = path.join(__dirname, '..');
const KEYS_DIR = path.join(ROOT, 'keys');

function fail(msg) {
  console.error(`[ci] ${msg}`);
  process.exit(1);
}

// 自动发现上传凭证：优先环境变量，其次扫描 keys/ 目录
function resolveCredentials() {
  if (process.env.WX_APPID && process.env.WX_PRIVATE_KEY_PATH) {
    return {
      appid: process.env.WX_APPID,
      keyPath: process.env.WX_PRIVATE_KEY_PATH,
    };
  }
  if (!fs.existsSync(KEYS_DIR)) {
    fail('未找到 keys/ 目录。请下载小程序代码上传密钥，保存为 keys/private.<appid>.key');
  }
  const keyFile = fs
    .readdirSync(KEYS_DIR)
    .find((f) => /^private\.(wx[0-9a-f]{16})\.key$/.test(f));
  if (!keyFile) {
    fail('keys/ 目录下未找到 private.<appid>.key 密钥文件');
  }
  const appid = keyFile.match(/^private\.(wx[0-9a-f]{16})\.key$/)[1];
  return { appid, keyPath: path.join(KEYS_DIR, keyFile) };
}

function createProject() {
  const { appid, keyPath } = resolveCredentials();
  console.log(`[ci] appid: ${appid}`);
  return new ci.Project({
    appid,
    type: 'miniProgram',
    projectPath: ROOT,
    privateKeyPath: keyPath,
    ignores: ['node_modules/**/*', 'scripts/**/*', 'keys/**/*', '*.md'],
  });
}

const BUILD_SETTING = {
  es6: true,
  postcss: true,
  minified: true,
  urlCheck: false,
};

async function upload() {
  const version = pkg.version;
  const result = await ci.upload({
    project: createProject(),
    version,
    desc: `点子铺 v${version} ${new Date().toLocaleString('zh-CN')}`,
    setting: BUILD_SETTING,
    onProgressUpdate: (info) => {
      if (info && info.message) console.log(`[ci] ${info.message}`);
    },
  });
  console.log(`[ci] v${version} 上传成功，体验版已更新`);
  return result;
}

async function preview() {
  const dest = path.join(ROOT, 'preview-qrcode.jpg');
  await ci.preview({
    project: createProject(),
    desc: `点子铺预览 ${new Date().toLocaleString('zh-CN')}`,
    setting: BUILD_SETTING,
    qrcodeFormat: 'image',
    qrcodeOutputDest: dest,
    onProgressUpdate: (info) => {
      if (info && info.message) console.log(`[ci] ${info.message}`);
    },
  });
  console.log(`[ci] 预览二维码已生成: ${dest}`);
}

(async () => {
  const action = process.argv[2];
  if (action === 'upload') return upload();
  if (action === 'preview') return preview();
  fail('未知命令，用法: node scripts/ci.js <upload|preview>');
})().catch((err) => {
  console.error('[ci] 执行失败:', (err && err.message) || err);
  process.exit(1);
});
