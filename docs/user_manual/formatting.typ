// The ATAK plugin template's manual layout, without the polylux package: TAK.gov's pipeline
// compiles the manual (gradle/typst.gradle), and a package would be downloaded at that moment.

#let accent_color = rgb(227, 119, 18)

// columns next to each other, e.g. an image and its text
#let side-by-side(columns: none, gutter: 1em, ..bodies) = {
  let cells = bodies.pos()
  let widths = if columns == none { (1fr,) * cells.len() } else { columns }
  grid(columns: widths, gutter: gutter, ..cells)
}

// one part of the manual, from a new page
#let tak-slide(body) = {
  pagebreak(weak: true)
  body
}

#let tak-title-slide(
  plugin-name: [],
  plugin-version: [],
  platform: [],
  platform-version: []) = page(
  background: image("titlepage.svg", width: 100%),
  footer: none,
)[
  #if platform != [] {
    place(
      top + left,
      dy: 80pt,
      dx: 260pt,
      text(font: "Libre Franklin", size: 30pt, weight: "bold", fill: white, platform)
    )
  }

  #set align(center + horizon)

  #text(size: 32pt, plugin-name)

  #set text(size: 12pt)
  Plug-in Version: #plugin-version \
  #if platform-version != [] and platform != [] [#platform #platform-version \ ]
  #datetime.today().display("[day] [month repr:long] [year]")
]

#let contents-slide() = page(
  header: align(center, underline(stroke: accent_color,
    strong(text(size: 24pt, "Contents")))),
  footer: none,
)[
  #align(center, box(width: 60%, align(left, outline(title: none, depth: 1))))
]

#let userguide(
  plugin-name: [],
  platform: [],
  platform-version: [],
  plugin-version: [],
  doc) = {

  set page(paper: "presentation-16-9")
  set par(justify: true)
  set text(font: "Latin Modern Sans", size: 14pt)

  tak-title-slide(
    plugin-name: plugin-name,
    plugin-version: plugin-version,
    platform-version: platform-version,
    platform: platform
  )

  contents-slide()

  set page(
    footer: context [
      #set align(right)
      #set text(10pt)
      #counter(page).display("1 / 1", both: true)
    ]
  )

  show heading.where(level: 1): it => block(below: 0.8em, {
    line(length: 100%, stroke: accent_color)
    text(accent_color, size: 20pt, underline(it.body))
  })
  show heading.where(level: 2): it => block(above: 1em, below: 0.6em,
    text(accent_color, size: 15pt, it.body))
  show table: set par(justify: false)

  doc
}
