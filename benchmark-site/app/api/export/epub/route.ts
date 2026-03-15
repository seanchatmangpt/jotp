import { NextRequest, NextResponse } from 'next/server'

interface ExportRequest {
  content: string
  title: string
  author?: string
  chapters?: { title: string; content: string }[]
}

// Mock EPUB generation - in production, this would use a proper EPUB library
async function generateEPUB(content: string, title: string, author?: string, chapters?: { title: string; content: string }[]): Promise<Buffer> {
  // This is a mock implementation
  // In production, you would use libraries like epub-gen or similar

  const chaptersWithContent = chapters || [
    { title: 'Content', content }
  ]

  const mockEPUBContent = `
<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <metadata>
    <dc:title>${title}</dc:title>
    ${author ? `<dc:creator>${author}</dc:creator>` : ''}
    <dc:language>en</dc:language>
    <dc:identifier id="id">${Date.now()}</dc:identifier>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    ${chaptersWithContent.map((_, index) =>
      `<item id="chapter${index}" href="chapter${index + 1}.xhtml" media-type="application/xhtml+xml"/>`
    ).join('\n    ')}
  </manifest>
  <spine toc="ncx">
    ${chaptersWithContent.map((_, index) =>
      `<itemref idref="chapter${index + 1}"/>`
    ).join('\n    ')}
  </spine>
</package>

Content files would be:
${chaptersWithContent.map((chapter, index) => `
chapter${index + 1}.xhtml:
<!DOCTYPE html>
<html>
<head>
  <title>${chapter.title}</title>
</head>
<body>
  <h1>${chapter.title}</h1>
  <div>${chapter.content}</div>
</body>
</html>
`).join('\n')}
  `

  // Return mock EPUB buffer
  return Buffer.from(mockEPUBContent, 'utf-8')
}

export async function POST(request: NextRequest) {
  try {
    const body: ExportRequest = await request.json()
    const { content, title, author, chapters } = body

    if (!content || !title) {
      return NextResponse.json(
        { error: 'Content and title are required' },
        { status: 400 }
      )
    }

    // Generate EPUB
    const epubBuffer = await generateEPUB(content, title, author, chapters)

    // Set headers for download
    const response = new NextResponse(epubBuffer as any, {
      status: 200,
      headers: {
        'Content-Type': 'application/epub+zip',
        'Content-Disposition': `attachment; filename="${title.replace(/[^a-z0-9]/gi, '_').toLowerCase()}.epub"`,
        'Content-Length': epubBuffer.byteLength.toString(),
      },
    })

    return response

  } catch (error) {
    console.error('EPUB generation error:', error)
    return NextResponse.json(
      { error: 'Failed to generate EPUB' },
      { status: 500 }
    )
  }
}