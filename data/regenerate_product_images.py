"""Regenerate images only. Never modifies products.json or indexed manuals."""
import argparse
import hashlib
import json
from html import escape
from pathlib import Path
from xml.etree import ElementTree
from generate_catalog import COLORS, GROUPS, MODELS, ROOT, shape


def illustration(product):
    base_name, series = product['name'].split(' · ')
    variant = MODELS.index(series.split(' ')[0])
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="512" height="400" viewBox="0 0 512 400" role="img">
<title>{escape(product['name'])}：原创商品概念图</title>
<desc>根据商品名称绘制的原创矢量示意，展示基本形态；并非真实商品照片。</desc>
<ellipse cx="256" cy="329" rx="124" ry="11" fill="#dce2e7"/>
{shape(product['category'], COLORS[variant], base_name, variant)}
</svg>'''


def protected_hashes():
    protected = [ROOT / 'data/products.json']
    protected += sorted((ROOT / 'data/manuals').glob('*.md'))
    protected += sorted((ROOT / 'web/public/manuals').glob('*.html'))
    return {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in protected}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--contact-sheet', type=Path, help='Optional local SVG contact sheet with one of each base product.')
    args = parser.parse_args()
    before = protected_hashes()
    products = json.loads((ROOT / 'data/products.json').read_text(encoding='utf-8'))
    target = ROOT / 'web/public/products'
    target.mkdir(parents=True, exist_ok=True)
    seen, representatives = set(), []
    for product in products:
        svg = illustration(product)
        ElementTree.fromstring(svg)
        (target / f"{product['sku']}.svg").write_text(svg, encoding='utf-8')
        name = product['name'].split(' · ')[0]
        if name not in seen:
            seen.add(name)
            representatives.append(product)
    expected = {name for group in GROUPS for name in group[3]}
    assert seen == expected, f'Uncovered base products: {expected - seen}'
    assert len(products) == 200 and len(seen) == 42
    assert before == protected_hashes(), 'Product catalogue or indexed manuals changed unexpectedly'
    if args.contact_sheet:
        columns, cell_width, cell_height = 6, 300, 270
        rows = (len(representatives) + columns - 1) // columns
        output = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{columns * cell_width}" height="{rows * cell_height + 90}" viewBox="0 0 {columns * cell_width} {rows * cell_height + 90}">', '<rect width="100%" height="100%" fill="#f8f9fb"/>', '<text x="28" y="39" font-family="Microsoft YaHei, sans-serif" font-size="24" fill="#273441">比特严选：42 种基础商品原创概念图</text>', '<text x="28" y="66" font-family="Microsoft YaHei, sans-serif" font-size="13" fill="#647582">按商品名称绘制，200 个颜色与比例变体。仅为演示示意，并非商品实拍。</text>']
        for index, product in enumerate(representatives):
            x, y = (index % columns) * cell_width, (index // columns) * cell_height + 90
            base_name = product['name'].split(' · ')[0]
            markup = illustration(product)
            inner = markup[markup.index('>') + 1:markup.rindex('</svg>')]
            output.append(f'<svg x="{x + 8}" y="{y}" width="284" height="222" viewBox="0 0 512 400">{inner}</svg>')
            output.append(f'<text x="{x + 150}" y="{y + 247}" text-anchor="middle" font-family="Microsoft YaHei, sans-serif" font-size="16" fill="#273441">{escape(base_name)}</text>')
        output.append('</svg>')
        args.contact_sheet.parent.mkdir(parents=True, exist_ok=True)
        args.contact_sheet.write_text('\n'.join(output), encoding='utf-8')
    print(f'Updated {len(products)} images for {len(seen)} distinct product forms. Catalogue and all {len(before) - 1} manuals unchanged.')


if __name__ == '__main__':
    main()
