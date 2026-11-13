# -*- coding: utf-8 -*-
"""web/index.html → android/assets/index.html，并在 </body> 前注入原生桥脚本。

注入内容只做一件事：把网页里 `a[download]` + `blob:` 的那次点击接管，
通过 AndroidSave 交回原生侧落盘（WebView 自身对 blob: 下载无反应）。
其余字节原样保留；重复执行幂等。

用法: python3 inject_shim.py <src.html> <dst.html>
"""
import io
import sys

SHIM = r'''
<!-- ===== 原生壳注入：blob 下载交给 Android 保存（不改变页面原有逻辑） ===== -->
<script>
(function () {
  if (window.__PIXELBONE_SHELL__) return;
  window.__PIXELBONE_SHELL__ = true;
  if (!window.AndroidSave) return;              // 浏览器里打开时保持原行为

  var blobs = new Map();                         // objectURL -> Blob
  var create = URL.createObjectURL;
  var revoke = URL.revokeObjectURL;

  URL.createObjectURL = function (obj) {
    var url = create.apply(URL, arguments);
    try { if (obj instanceof Blob) blobs.set(url, obj); } catch (e) {}
    return url;
  };
  URL.revokeObjectURL = function (url) {
    blobs["delete"](url);
    try { return revoke.apply(URL, arguments); } catch (e) {}
  };

  var click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    try {
      var blob = blobs.get(this.href);
      if (blob) {
        var name = this.download || 'export.bin';
        var fr = new FileReader();
        fr.onload = function () {
          try {
            var s = String(fr.result);
            AndroidSave.save(name, blob.type || 'application/octet-stream', s.slice(s.indexOf(',') + 1));
          } catch (e) { AndroidSave.toast('读取导出数据失败：' + e); }
        };
        fr.onerror = function () { AndroidSave.toast('读取导出数据失败'); };
        fr.readAsDataURL(blob);
        return;                                   // 阻止 WebView 内无效的 blob 下载
      }
    } catch (e) {}
    return click.apply(this, arguments);
  };
})();
</script>
'''


def main(src, dst):
    with io.open(src, 'r', encoding='utf-8') as f:
        html = f.read()
    if '__PIXELBONE_SHELL__' not in html:
        i = html.rindex('</body>')
        html = html[:i] + SHIM + html[i:]
    with io.open(dst, 'w', encoding='utf-8') as f:
        f.write(html)
    print('    %s -> %s (%d bytes, shim injected)' % (src, dst, len(html.encode('utf-8'))))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
