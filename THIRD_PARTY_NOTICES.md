# Third-party notices

DSH Mobile bundles or derives from the third-party material listed below. Runtime dependencies
resolved by Gradle carry their own licences; this file covers material that ships *inside* this
repository's sources.

## Feather

Fifteen glyphs from the Feather icon set are traced as Compose `ImageVector`s in
`app/src/main/java/com/labteto/dshmobile/ui/components/FeatherIcons.kt`, and the few more the file
viewer needs (download, expand, play, pause, zoom, wrap and similar) in
`app/src/main/java/com/labteto/dshmobile/ui/screens/main/preview/PreviewIcons.kt`.

- Project: https://feathericons.com
- Source: https://github.com/feathericons/feather
- Licence: MIT

```
MIT License

Copyright (c) 2013-2023 Cole Bemis

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```


## xterm.js

The terminal renderer bundles `@xterm/xterm` **5.5.0** and `@xterm/addon-fit`
**0.10.0**, downloaded from their npm distribution archives. Runtime code and CSS
are in `app/src/main/assets/terminal/`; their complete MIT license texts are kept
there as `LICENSE.xterm` and `LICENSE.addon-fit`.

- Project: https://github.com/xtermjs/xterm.js
- Sources: https://registry.npmjs.org/@xterm/xterm/-/xterm-5.5.0.tgz
  and https://registry.npmjs.org/@xterm/addon-fit/-/addon-fit-0.10.0.tgz
- No remote scripts or CDN resources are loaded by the terminal screen.
