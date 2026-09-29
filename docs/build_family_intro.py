from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT
from docx.shared import Cm, Pt, RGBColor
from docx.oxml import OxmlElement
from docx.oxml.ns import qn


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "docs" / "小程序介绍材料"
IMG_DIR = OUT_DIR / "页面图片"
DOCX_PATH = OUT_DIR / "恒丰五金店小程序介绍.docx"
OUT_DIR.mkdir(parents=True, exist_ok=True)
IMG_DIR.mkdir(parents=True, exist_ok=True)

FONT_REG = Path(r"C:\Windows\Fonts\msyh.ttc")
FONT_BOLD = Path(r"C:\Windows\Fonts\msyhbd.ttc")


def f(size, bold=False):
    path = FONT_BOLD if bold and FONT_BOLD.exists() else FONT_REG
    return ImageFont.truetype(str(path), size)


COLORS = {
    "bg": "#F5F7F6",
    "card": "#FFFFFF",
    "text": "#182126",
    "muted": "#6B7772",
    "line": "#DCE2DF",
    "blue": "#2865D7",
    "blue_soft": "#EAF1FF",
    "green": "#247A55",
    "green_soft": "#E3F3EA",
    "orange": "#A84F19",
    "orange_soft": "#FFF0DF",
    "red": "#B95034",
    "red_soft": "#FCEBE6",
    "purple": "#7051AA",
    "purple_soft": "#EEE9FA",
    "navy": "#1C3150",
}


def rounded(draw, box, radius, fill, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def text(draw, xy, value, size, fill=None, bold=False, anchor=None):
    draw.text(xy, value, font=f(size, bold), fill=fill or COLORS["text"], anchor=anchor)


def base_screen(title, active_tab=None):
    img = Image.new("RGB", (750, 1334), COLORS["bg"])
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 750, 92), fill="#FFFFFF")
    text(d, (32, 32), "9:41", 22, bold=True)
    text(d, (375, 58), title, 31, bold=True, anchor="mm")
    text(d, (700, 32), "•••", 22, anchor="ra")
    d.line((0, 91, 750, 91), fill="#E5E9E7", width=2)
    if active_tab:
        d.rectangle((0, 1220, 750, 1334), fill="#FFFFFF")
        d.line((0, 1220, 750, 1220), fill="#E2E6E4", width=2)
        tabs = [("首", "首页"), ("品", "商品"), ("单", "单据"), ("库", "库存"), ("小", "小五")]
        for i, (mark, label) in enumerate(tabs):
            x = 75 + i * 150
            color = COLORS["blue"] if label == active_tab else "#78827E"
            text(d, (x, 1250), mark, 26, color, True, "mm")
            text(d, (x, 1293), label, 20, color, label == active_tab, "mm")
    return img, d


def save_home():
    img, d = base_screen("首页", "首页")
    text(d, (34, 128), "今天想做什么？", 42, bold=True)
    text(d, (34, 182), "老板，常用的直接点，麻烦的交给小五。", 23, COLORS["muted"])
    rounded(d, (652, 120, 716, 184), 32, COLORS["blue"])
    text(d, (684, 152), "管", 25, "white", True, "mm")
    rounded(d, (30, 215, 720, 278), 18, "#E9EFEC")
    d.ellipse((50, 238, 64, 252), fill=COLORS["green"])
    text(d, (78, 236), "数据已更新", 21, "#43514B", True)
    text(d, (230, 236), "更新于 09:41", 20, COLORS["muted"])
    text(d, (650, 236), "下拉刷新", 20, COLORS["blue"], anchor="ra")
    text(d, (32, 315), "常用操作", 30, bold=True)
    text(d, (710, 320), "大按钮，直接进入", 19, COLORS["muted"], anchor="ra")
    actions = [
        ("单", "开销售单", "选客户和商品", COLORS["orange"], COLORS["orange_soft"]),
        ("查", "查商品", "看价格和库存", "#245FBE", "#E7F0FF"),
        ("入", "采购入库", "到货后登记", COLORS["green"], COLORS["green_soft"]),
        ("盘", "库存盘点", "核对实际数量", COLORS["purple"], COLORS["purple_soft"]),
    ]
    for i, (mark, title, desc, color, soft) in enumerate(actions):
        col, row = i % 2, i // 2
        x1, y1 = 30 + col * 353, 360 + row * 145
        rounded(d, (x1, y1, x1 + 337, y1 + 128), 20, "white", COLORS["line"], 2)
        rounded(d, (x1 + 18, y1 + 28, x1 + 88, y1 + 98), 20, soft)
        text(d, (x1 + 53, y1 + 63), mark, 25, color, True, "mm")
        text(d, (x1 + 106, y1 + 34), title, 25, bold=True)
        text(d, (x1 + 106, y1 + 73), desc, 19, COLORS["muted"])
    text(d, (32, 672), "一眼看看", 30, bold=True)
    rounded(d, (30, 718, 720, 948), 20, "white", COLORS["line"], 2)
    rows = [("销", "今天卖了多少", "¥ 1,280", COLORS["blue"]), ("货", "快没货的商品", "3 件", COLORS["red"]), ("品", "店里有多少商品", "126 件", COLORS["blue"])]
    for i, (mark, label, value, color) in enumerate(rows):
        y = 730 + i * 72
        if i: d.line((50, y, 700, y), fill="#E4E9E6", width=2)
        rounded(d, (50, y + 12, 98, y + 60), 14, COLORS["red_soft"] if mark == "货" else COLORS["blue_soft"])
        text(d, (74, y + 36), mark, 19, color, True, "mm")
        text(d, (118, y + 23), label, 23, bold=True)
        text(d, (680, y + 25), value, 24, color, True, "ra")
    rounded(d, (30, 980, 720, 1185), 24, COLORS["navy"])
    rounded(d, (52, 1008, 116, 1072), 20, COLORS["blue"])
    text(d, (84, 1040), "小", 23, "white", True, "mm")
    text(d, (138, 1008), "复杂的事，交给小五", 27, "white", True)
    text(d, (138, 1050), "复杂开单、模糊查找、经营分析，用一句话说明。", 18, "#C0CCDB")
    rounded(d, (52, 1100, 698, 1165), 17, "white")
    text(d, (72, 1120), "按住说话，或点这里打字", 20, "#66758B")
    path = IMG_DIR / "01首页.png"
    img.save(path)
    return path


def save_products():
    img, d = base_screen("商品管理", "商品")
    text(d, (32, 126), "商品管理", 38, bold=True)
    text(d, (32, 176), "名称、条码和库存都能直接查", 22, COLORS["muted"])
    rounded(d, (612, 126, 718, 178), 16, COLORS["blue"])
    text(d, (665, 152), "＋ 新增", 20, "white", True, "mm")
    for i, (value, label, color) in enumerate([("126", "商品总数", COLORS["text"]), ("3", "低库存", COLORS["red"]), ("8", "已下架", COLORS["muted"])]):
        x = 34 + i * 235
        text(d, (x, 235), value, 34, color, True)
        text(d, (x, 278), label, 19, COLORS["muted"])
    rounded(d, (30, 326, 720, 390), 18, "white", COLORS["line"], 2)
    text(d, (56, 345), "搜索名称 / 条码 / 厂商", 21, "#8A9590")
    products = [
        ("东成充电电钻", "DCJZ1202", "零售 ¥399  批发 ¥360", "库存 8 · A-03-2", True),
        ("不锈钢膨胀螺丝", "M8×80", "零售 ¥2.5  批发 ¥1.8", "库存 260 · B-01-4", False),
        ("公牛五孔插座", "G07Z223", "零售 ¥18  批发 ¥15", "库存 42 · C-02-1", False),
        ("大白鲨切割片", "105×1.2", "零售 ¥6  批发 ¥4.8", "库存 6 · A-05-3", True),
    ]
    for i, (name, spec, prices, stock, warn) in enumerate(products):
        y = 425 + i * 176
        rounded(d, (30, y, 720, y + 156), 20, "white", COLORS["line"], 2)
        rounded(d, (50, y + 25, 122, y + 97), 16, COLORS["blue_soft"])
        text(d, (86, y + 61), "品", 24, COLORS["blue"], True, "mm")
        text(d, (145, y + 20), name, 25, bold=True)
        text(d, (145, y + 57), spec, 18, COLORS["muted"])
        text(d, (145, y + 88), prices, 19, COLORS["text"])
        text(d, (145, y + 119), stock, 19, COLORS["muted"])
        if warn:
            rounded(d, (600, y + 20, 694, y + 54), 12, COLORS["red_soft"])
            text(d, (647, y + 37), "低库存", 16, COLORS["red"], True, "mm")
    path = IMG_DIR / "02商品管理.png"
    img.save(path)
    return path


def save_sales():
    img, d = base_screen("销售单据", "单据")
    text(d, (32, 126), "销售单据", 38, bold=True)
    text(d, (32, 176), "卖了什么、收了多少、欠了多少，都记在一张单里", 21, COLORS["muted"])
    rounded(d, (610, 126, 718, 178), 16, COLORS["blue"])
    text(d, (664, 152), "＋ 新建", 20, "white", True, "mm")
    rounded(d, (30, 220, 720, 328), 20, "white", COLORS["line"], 2)
    stats = [("18", "销售单"), ("¥ 5,860", "销售金额"), ("¥ 620", "待收欠款")]
    for i, (value, label) in enumerate(stats):
        x = 68 + i * 225
        text(d, (x, 244), value, 29, COLORS["red"] if i == 2 else COLORS["text"], True)
        text(d, (x, 286), label, 18, COLORS["muted"])
    orders = [
        ("XS20260929008", "老王五金加工", "¥ 560", "已收 ¥300 · 欠款 ¥260", "待出库"),
        ("XS20260929007", "散客", "¥ 128", "已收 ¥128 · 欠款 ¥0", "已完成"),
        ("XS20260928021", "宏盛装修队", "¥ 1,980", "已收 ¥1,000 · 欠款 ¥980", "已出库"),
        ("XS20260928020", "李师傅", "¥ 86", "已收 ¥86 · 欠款 ¥0", "已完成"),
    ]
    for i, (no, customer, total, pay, status) in enumerate(orders):
        y = 365 + i * 180
        rounded(d, (30, y, 720, y + 158), 20, "white", COLORS["line"], 2)
        text(d, (52, y + 22), no, 18, COLORS["muted"])
        text(d, (52, y + 57), customer, 25, bold=True)
        text(d, (686, y + 57), total, 25, COLORS["text"], True, "ra")
        text(d, (52, y + 96), pay, 19, COLORS["red"] if "欠款 ¥0" not in pay else COLORS["green"])
        soft = COLORS["orange_soft"] if status == "待出库" else COLORS["green_soft"]
        color = COLORS["orange"] if status == "待出库" else COLORS["green"]
        rounded(d, (590, y + 100, 687, y + 135), 12, soft)
        text(d, (638, y + 118), status, 16, color, True, "mm")
    path = IMG_DIR / "03销售单据.png"
    img.save(path)
    return path


def save_inventory():
    img, d = base_screen("库存管理", "库存")
    text(d, (32, 126), "实时库存", 38, bold=True)
    text(d, (32, 176), "需要盘点时，直接进入对应商品调整", 22, COLORS["muted"])
    rounded(d, (30, 220, 720, 340), 20, "white", COLORS["line"], 2)
    metrics = [("126", "库存商品"), ("2,846", "库存总数"), ("3", "库存预警")]
    for i, (value, label) in enumerate(metrics):
        x = 65 + i * 225
        text(d, (x, 244), value, 32, COLORS["red"] if i == 2 else COLORS["text"], True)
        text(d, (x, 290), label, 18, COLORS["muted"])
    rounded(d, (30, 372, 720, 445), 18, COLORS["red_soft"])
    text(d, (55, 393), "3 件商品建议补货", 23, COLORS["red"], True)
    text(d, (690, 395), "预警数量会标红", 18, COLORS["muted"], anchor="ra")
    rounded(d, (30, 474, 720, 538), 18, "white", COLORS["line"], 2)
    text(d, (55, 493), "搜索商品名称 / 条码", 21, "#8A9590")
    items = [("东成充电电钻", "8", "10", "A-03-2"), ("大白鲨切割片", "6", "10", "A-05-3"), ("角磨机碳刷", "4", "8", "B-06-2"), ("不锈钢膨胀螺丝", "260", "30", "B-01-4")]
    for i, (name, qty, threshold, loc) in enumerate(items):
        y = 570 + i * 145
        rounded(d, (30, y, 720, y + 126), 20, "white", COLORS["line"], 2)
        text(d, (52, y + 22), name, 24, bold=True)
        text(d, (52, y + 62), f"库存 {qty} · 预警值 {threshold} · 库位 {loc}", 19, COLORS["muted"])
        warn = int(qty) <= int(threshold)
        rounded(d, (575, y + 20, 690, y + 57), 12, COLORS["red_soft"] if warn else COLORS["green_soft"])
        text(d, (632, y + 39), "库存预警" if warn else "库存正常", 16, COLORS["red"] if warn else COLORS["green"], True, "mm")
        rounded(d, (552, y + 78, 690, y + 111), 12, "white", COLORS["blue"], 2)
        text(d, (621, y + 95), "盘点调整", 16, COLORS["blue"], True, "mm")
    path = IMG_DIR / "04库存管理.png"
    img.save(path)
    return path


def save_agent():
    img, d = base_screen("小五", "小五")
    rounded(d, (30, 124, 94, 188), 20, COLORS["blue"])
    text(d, (62, 156), "小", 24, "white", True, "mm")
    text(d, (116, 128), "小五", 34, bold=True)
    text(d, (116, 170), "复杂开单、模糊查找和经营分析  ·  可用", 19, COLORS["muted"])
    rounded(d, (30, 214, 720, 267), 15, "#E9EFEC")
    text(d, (375, 240), "查数据直接完成  ·  正式操作由你确认", 19, "#43514B", True, "mm")
    text(d, (32, 308), "可以这样跟小五说", 28, bold=True)
    prompts = ["查一下电钻库存", "给老王开两把电钻，按以前价格记账", "今天卖了多少钱"]
    for i, prompt in enumerate(prompts):
        y = 352 + i * 78
        rounded(d, (30, y, 720, y + 62), 17, "white", COLORS["line"], 2)
        rounded(d, (48, y + 13, 84, y + 49), 12, COLORS["blue_soft"])
        text(d, (66, y + 31), str(i + 1), 16, COLORS["blue"], True, "mm")
        text(d, (104, y + 19), prompt, 20)
        text(d, (690, y + 19), "›", 26, COLORS["muted"], anchor="ra")
    text(d, (690, 625), "给老王开两把电钻，按以前价格记账", 20, "white", False, "ra")
    rounded(d, (260, 608, 710, 664), 20, COLORS["blue"])
    text(d, (690, 625), "给老王开两把电钻，按以前价格记账", 20, "white", False, "ra")
    text(d, (32, 702), "小五", 18, COLORS["muted"], True)
    rounded(d, (30, 732, 720, 1050), 22, "white", COLORS["line"], 2)
    text(d, (54, 755), "请确认销售草稿", 27, bold=True)
    text(d, (54, 796), "客户  老王五金加工", 20, COLORS["muted"])
    fields = [("订单金额", "¥560"), ("已收金额", "¥0"), ("新增欠款", "¥560"), ("提交后欠款", "¥660")]
    for i, (label, value) in enumerate(fields):
        col, row = i % 2, i // 2
        x, y = 54 + col * 323, 840 + row * 78
        text(d, (x, y), label, 17, COLORS["muted"])
        text(d, (x, y + 28), value, 23, COLORS["red"] if "欠款" in label else COLORS["text"], True)
    rounded(d, (54, 990, 342, 1035), 14, "white", COLORS["line"], 2)
    text(d, (198, 1012), "取消操作", 18, COLORS["muted"], True, "mm")
    rounded(d, (365, 990, 696, 1035), 14, COLORS["blue"])
    text(d, (530, 1012), "确认执行", 18, "white", True, "mm")
    rounded(d, (30, 1092, 610, 1165), 18, "white", COLORS["line"], 2)
    text(d, (52, 1116), "例如：查一下电钻库存", 20, "#8A9590")
    rounded(d, (628, 1092, 720, 1165), 18, COLORS["blue"])
    text(d, (674, 1128), "发送", 19, "white", True, "mm")
    path = IMG_DIR / "05小五助手.png"
    img.save(path)
    return path


def set_cell_shading(cell, fill):
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:fill"), fill)
    tc_pr.append(shd)


def set_cell_margins(cell, top=120, start=150, bottom=120, end=150):
    tc = cell._tc
    tcPr = tc.get_or_add_tcPr()
    tcMar = tcPr.first_child_found_in("w:tcMar")
    if tcMar is None:
        tcMar = OxmlElement("w:tcMar")
        tcPr.append(tcMar)
    for m, v in (("top", top), ("start", start), ("bottom", bottom), ("end", end)):
        node = tcMar.find(qn(f"w:{m}"))
        if node is None:
            node = OxmlElement(f"w:{m}")
            tcMar.append(node)
        node.set(qn("w:w"), str(v))
        node.set(qn("w:type"), "dxa")


def set_font(run, name="Microsoft YaHei", size=11, bold=False, color="000000"):
    run.font.name = name
    run._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), name)
    run.font.size = Pt(size)
    run.bold = bold
    run.font.color.rgb = RGBColor.from_string(color)


def add_paragraph(doc, content="", size=11, bold=False, color="323C38", align=None, before=0, after=6, line=1.35):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(before)
    p.paragraph_format.space_after = Pt(after)
    p.paragraph_format.line_spacing = line
    if align is not None:
        p.alignment = align
    r = p.add_run(content)
    set_font(r, size=size, bold=bold, color=color)
    return p


def add_bullets(doc, items):
    for item in items:
        p = doc.add_paragraph(style="List Bullet")
        p.paragraph_format.left_indent = Cm(0.45)
        p.paragraph_format.first_line_indent = Cm(-0.18)
        p.paragraph_format.space_after = Pt(5)
        p.paragraph_format.line_spacing = 1.3
        r = p.add_run(item)
        set_font(r, size=10.5, color="323C38")


def add_heading(doc, value, level=1):
    p = doc.add_paragraph(style=f"Heading {level}")
    p.paragraph_format.space_before = Pt(2 if level == 1 else 8)
    p.paragraph_format.space_after = Pt(7)
    p.paragraph_format.keep_with_next = True
    r = p.add_run(value)
    set_font(r, size=18 if level == 1 else 13, bold=True, color="000000")
    return p


def add_image(doc, path, width_cm=8.3, caption=None):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_before = Pt(2)
    p.paragraph_format.space_after = Pt(3)
    p.paragraph_format.keep_with_next = caption is not None
    picture = p.add_run().add_picture(str(path), width=Cm(width_cm))
    picture._inline.docPr.set("title", path.stem)
    picture._inline.docPr.set("descr", caption or path.stem)
    if caption:
        cp = add_paragraph(doc, caption, size=8.5, color="737D78", align=WD_ALIGN_PARAGRAPH.CENTER, after=8, line=1.0)
        cp.paragraph_format.keep_with_next = True


def page_break(doc):
    doc.add_page_break()


def build_doc(images):
    doc = Document()
    sec = doc.sections[0]
    sec.top_margin = Cm(1.55)
    sec.bottom_margin = Cm(1.45)
    sec.left_margin = Cm(1.75)
    sec.right_margin = Cm(1.75)
    sec.page_width = Cm(21)
    sec.page_height = Cm(29.7)

    styles = doc.styles
    normal = styles["Normal"]
    normal.font.name = "Microsoft YaHei"
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    normal.font.size = Pt(11)
    for name, size in (("Title", 27), ("Heading 1", 18), ("Heading 2", 13)):
        style = styles[name]
        style.font.name = "Microsoft YaHei"
        style._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
        style.font.size = Pt(size)
        style.font.color.rgb = RGBColor(0, 0, 0)
        style.font.bold = True

    title = doc.add_paragraph(style="Title")
    title.alignment = WD_ALIGN_PARAGRAPH.CENTER
    title.paragraph_format.space_before = Pt(8)
    title.paragraph_format.space_after = Pt(5)
    r = title.add_run("恒丰五金店小程序介绍")
    set_font(r, size=27, bold=True, color="000000")
    add_paragraph(doc, "给爸妈看的初步版本说明", size=12, color="59645F", align=WD_ALIGN_PARAGRAPH.CENTER, after=8)
    add_image(doc, images[0], width_cm=7.1, caption="当前首页界面  页面中的金额和数量为演示数据")
    add_paragraph(doc, "我做这个小程序，是想把店里平时要查的商品、进货、卖货、库存、客户和欠账，逐步放进手机里统一管理。以后需要找价格、看库存或查一笔旧账时，可以先在微信里查，不必完全靠记忆或翻不同的本子。", size=11, after=7)
    add_paragraph(doc, "现在还是初步版本。请你们先看它是否符合店里的做事习惯，再告诉我哪些地方不实用、哪些步骤太麻烦、还缺哪些功能。我会按照店里的真实情况继续修改。", size=11, bold=True, color="1C3150", after=0)

    page_break(doc)
    add_heading(doc, "首页能直接找到常用事情")
    add_paragraph(doc, "打开小程序后，最常用的事情会放在首页的大按钮里。需要开单、查商品、登记进货或盘点库存，可以直接进入，不用在很多菜单里来回找。", after=6)
    add_image(doc, images[0], width_cm=7.6, caption="首页把常用操作和当天需要关注的事情放在一起")
    add_bullets(doc, [
        "开销售单  选择客户和商品，记录数量、价格、收款和欠款",
        "查商品  按名称、条码或厂家查价格、库存和库位",
        "采购入库  到货后登记供应商、商品、数量和进价",
        "库存盘点  核对手机记录和店里实际数量是否一致",
        "一眼看看  查看今天销售、缺货提醒和商品数量",
    ])

    page_break(doc)
    add_heading(doc, "商品信息放在一个地方")
    add_paragraph(doc, "每种商品可以记录名称、规格、条码、价格、数量、库位和供货厂家。顾客来问价时，先搜索商品，就能看到零售价、批发价和老客户价。", after=5)
    add_image(doc, images[1], width_cm=7.6, caption="商品页面可以搜索，也会标出库存不足的商品")
    add_bullets(doc, [
        "按名称、条码或厂家搜索商品",
        "分别记录零售价、批发价和老客户价",
        "查看现有库存和摆放位置",
        "库存低于设定数量时提醒补货",
        "商品资料有变化时可以继续修改",
    ])
    add_paragraph(doc, "希望你们补充  商品平时按什么名称最好找，是否需要记录品牌、颜色、长短、箱装数量或不同单位之间的换算。", size=10.5, bold=True, color="1C3150", before=3)

    page_break(doc)
    add_heading(doc, "销售和欠账一起记录")
    add_paragraph(doc, "卖货时可以建立一张销售单，记录卖给谁、卖了哪些商品、实际成交价、已经收款多少，以及还欠多少。完成出库后，商品库存会跟着减少。", after=5)
    add_image(doc, images[2], width_cm=7.6, caption="销售单据把总金额 已收金额和欠款放在同一处")
    add_bullets(doc, [
        "普通散客、批发客户和老客户可以使用不同价格",
        "成交时仍可按实际情况调整价格",
        "记录现金、微信等收款以及尚未收回的欠款",
        "进入客户资料后，可以查看以前的销售单和欠款记录",
        "以后查账时，可以按客户和时间查看历史记录",
    ])
    add_paragraph(doc, "希望你们补充  平时怎样给熟客定价，欠款通常记到个人还是单位名下，收回欠款时需要保留哪些说明。", size=10.5, bold=True, color="1C3150", before=3)

    page_break(doc)
    add_heading(doc, "进货后库存可以跟着变化")
    add_paragraph(doc, "厂家送货后，可以建立采购单，记录从谁那里进货、进了什么、数量和进价。确认入库后，库存增加；卖货确认出库后，库存减少。", after=5)
    add_image(doc, images[3], width_cm=7.6, caption="库存页面会显示当前数量 预警值和商品库位")
    add_bullets(doc, [
        "查看每种商品当前还有多少",
        "只看库存不足、需要补货的商品",
        "盘点时填写实际数量并说明差异原因",
        "查看以前的入库、出库和盘点调整记录",
        "通过库位信息更快找到商品",
    ])
    add_paragraph(doc, "希望你们补充  进货时现在怎样清点，库位要记到多细，破损、赠品、退货和整箱拆零应该怎样算。", size=10.5, bold=True, color="1C3150", before=3)

    page_break(doc)
    add_heading(doc, "小五可以用说话的方式帮忙")
    add_paragraph(doc, "小程序里有一个叫小五的助手。以后可以像平时说话一样，让它查询商品和库存，也可以让它准备一张销售草稿。", after=5)
    add_image(doc, images[4], width_cm=7.6, caption="小五先展示操作结果  正式改账和开单前仍要人工确认")
    add_bullets(doc, [
        "可以问  电钻还有多少库存",
        "可以问  老王现在还欠多少钱",
        "可以说  给老王开两把电钻 按以前价格记账",
        "查询可以直接显示结果",
        "正式开单、改库存和改账目之前，必须由人确认",
    ])
    add_paragraph(doc, "小五目前仍在完善。它可以帮助查询和准备操作，但重要账目最终仍以我们确认的内容为准。", size=10.5, bold=True, color="1C3150", before=3)

    page_break(doc)
    add_heading(doc, "目前已经做出的内容")
    add_paragraph(doc, "初步版本已经包含下面这些页面和操作，主要用于让我们一起确认方向和使用方法。")
    table = doc.add_table(rows=1, cols=2)
    table.autofit = False
    table.columns[0].width = Cm(4.0)
    table.columns[1].width = Cm(12.8)
    headers = ["部分", "目前可以做的事情"]
    for i, value in enumerate(headers):
        c = table.rows[0].cells[i]
        set_cell_shading(c, "1C3150")
        set_cell_margins(c)
        c.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
        p = c.paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        r = p.add_run(value)
        set_font(r, size=10.5, bold=True, color="FFFFFF")
    tr_pr = table.rows[0]._tr.get_or_add_trPr()
    tbl_header = OxmlElement("w:tblHeader")
    tbl_header.set(qn("w:val"), "true")
    tr_pr.append(tbl_header)
    rows = [
        ("商品", "新增 编辑 搜索 查看价格 库存和库位"),
        ("客户", "记录联系方式 客户类型 历史销售和欠款"),
        ("供应商", "记录联系人和电话 查看采购历史"),
        ("采购", "建立采购单 记录进货明细 确认入库"),
        ("销售", "建立销售单 记录实收和欠款 确认出库"),
        ("库存", "查询库存 库存预警 盘点调整 查看日志"),
        ("经营情况", "查看采购 销售和库存汇总 生成导出结果"),
        ("小五", "用日常说话的方式查询和准备复杂操作"),
    ]
    for idx, (part, detail) in enumerate(rows):
        cells = table.add_row().cells
        if idx % 2:
            set_cell_shading(cells[0], "F2F6FB")
            set_cell_shading(cells[1], "F2F6FB")
        for c in cells:
            set_cell_margins(c, top=100, bottom=100)
            c.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
        p = cells[0].paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        set_font(p.add_run(part), size=10.5, bold=True, color="243A57")
        set_font(cells[1].paragraphs[0].add_run(detail), size=10.5, color="323C38")
    add_paragraph(doc, "后面还可以继续增加或调整  扫码开单 单位换算 打印送货单 微信发送对账单 退货处理 促销价格 更细的经营报表等。是否需要做，要由店里的实际用法来决定。", size=10.5, before=10, after=0)

    page_break(doc)
    add_heading(doc, "请你们按平时做生意的方法提意见")
    add_paragraph(doc, "不用考虑技术，也不用一次把所有需求想全。看到哪个页面，就说平时真正是怎么做的。下面这些问题可以帮助我们一起补充。", after=10)
    questions = [
        "顾客来买东西时，从问价到拿货、收款、记账，实际顺序是什么",
        "哪些商品最难找价格、规格或摆放位置",
        "零售客户、批发客户和熟客的价格平时怎么定",
        "客户欠款和厂家欠款现在分别怎样记录",
        "进货后怎样清点，整箱和零个怎样换算",
        "盘点时最常见的差异原因是什么",
        "每天最想在首页先看到哪几项内容",
        "哪些步骤如果要在手机上操作，会觉得太麻烦",
        "还希望这个小程序帮忙解决什么问题",
    ]
    for i, q in enumerate(questions, 1):
        p = doc.add_paragraph()
        p.paragraph_format.space_after = Pt(9)
        p.paragraph_format.line_spacing = 1.25
        r1 = p.add_run(f"{i}  ")
        set_font(r1, size=11, bold=True, color="2865D7")
        r2 = p.add_run(q)
        set_font(r2, size=11, color="29332F")
        r3 = p.add_run("\n意见  _________________________________________________")
        set_font(r3, size=10, color="7A8580")
    add_paragraph(doc, "你们只要告诉我哪里不符合实际、哪里不好用、还缺什么，我就能继续往下改。小程序最终要按照店里的习惯来做，越简单、越顺手越好。", size=11, bold=True, color="1C3150", before=6, after=0)

    doc.core_properties.title = "恒丰五金店小程序介绍"
    doc.core_properties.subject = "面向家人的小程序功能图文说明和需求收集"
    doc.core_properties.author = ""
    doc.save(DOCX_PATH)
    return DOCX_PATH


if __name__ == "__main__":
    images = [save_home(), save_products(), save_sales(), save_inventory(), save_agent()]
    print(build_doc(images))
