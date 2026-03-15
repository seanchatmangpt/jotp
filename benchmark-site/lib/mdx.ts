import { createMDX } from 'next-mdx-remote/rsc'
import { DocFrontmatter, DocFrontmatterSchema } from './content-schema'
import { MDXComponents } from './mdx-components'

export const mdx = createMDX({
  options: {
    parseFrontmatter: async (file) => {
      const frontmatter = file.frontmatter
      const parsed = DocFrontmatterSchema.parse(frontmatter)
      return { data: parsed, content: file.content }
    },
    components: MDXComponents,
  },
})

export async function parseFrontmatter(file: string): Promise<{
  data: DocFrontmatter
  content: string
}> {
  return mdx.process(file)
}