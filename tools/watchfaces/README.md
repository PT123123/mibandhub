# 内置表盘的生成工具

`app/src/main/assets/watchfaces/` 里两张自制表盘（薄荷极简 / 琥珀极简）
就是用 [make_faces.py](make_faces.py) 生成的。社区那张（Digital Codex）
来自 amazfitwatchfaces，不在此流程内。

在线市场（`market/`）的批量生成用 [make_market.py](make_market.py)：
2 布局 × 4 配色 = 8 张，打包后自动补丁唯一表盘 ID（UIHH 头 offset 18..21）
并写 `market/index.json`。

## 依赖

- Python 3 + Pillow（画素材）
- Node.js + npm（`watchface-js` 做 .bin 打包）

## 步骤（在一个临时目录里跑，别在仓库里跑）

```bash
npm init -y && npm i watchface-js          # 提供 wfjs CLI
cp <仓库>/tools/watchfaces/make_faces.py .
python make_faces.py                       # 产出 face_mint/ face_amber/
npx wfjs writeBin -i face_mint -m miband5  # → face_mint_packed.bin
npx wfjs writeBin -i face_amber -m miband5 # → face_amber_packed.bin
```

校验：`npx wfjs readBin -i face_mint_packed.bin -m miband5` 能解回来、
`watchface.json` 与源一致、大小 < 615 KB 即合格。预览图就是各目录里
`Background.PreviewEN` 指向的那张 PNG。

之后把 `.bin` / 预览 `.png` 拷进 `app/src/main/assets/watchfaces/`，
并在 `manifest.json` 里补一条（id / name / author / source / license /
file / preview / sizeBytes / note）。

## 布局模板的来历

`watchface.json` 的块结构照抄 Digital Codex（amazfitwatchfaces #5183）
解包出来的真实表盘，只保留 Background / Time / Activity(Steps) / Date /
Battery 这几个已验证的块。格式要点（详见 `docs/watchface.md` §5.1）：
容器是 UIHH 头的华米 .bin；格式里 alpha 反相存储（文件里 0x00 = 不透明）；
数字条要求同一组里每张图同尺寸。
