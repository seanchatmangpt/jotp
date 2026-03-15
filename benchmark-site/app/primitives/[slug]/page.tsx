import { Metadata } from 'next';
import { notFound } from 'next/navigation';
import Link from 'next/link';
import { Box, Container, Flex, Text } from "@radix-ui/themes";
import { getComponentById } from '@/lib/data/jotp-architecture';
import { PrimitiveDetail } from '@/components/primitive/primitive-detail';

interface PrimitivePageProps {
  params: Promise<{
    slug: string;
  }>;
}

// Generate metadata for SEO
export async function generateMetadata(
  props: PrimitivePageProps
): Promise<Metadata> {
  const params = await props.params;
  const component = getComponentById(params.slug);

  if (!component) {
    return {
      title: 'Primitive Not Found',
    };
  }

  return {
    title: `${component.name} - JOTP Primitive`,
    description: component.description,
    keywords: [
      component.name,
      component.category,
      ...(component.otpEquivalent ? [component.otpEquivalent] : []),
      'JOTP',
      'Java OTP',
      'fault tolerance',
      'supervision',
    ],
    openGraph: {
      title: `${component.name} - JOTP Primitive`,
      description: component.description,
      type: 'article',
    },
  };
}

// Generate static params for all primitives
export async function generateStaticParams() {
  const { JOTP_COMPONENTS } = await import('@/lib/data/jotp-architecture');
  return JOTP_COMPONENTS.map((component) => ({
    slug: component.id,
  }));
}

export default async function PrimitivePage(props: PrimitivePageProps) {
  const params = await props.params;
  const component = getComponentById(params.slug);

  if (!component) {
    notFound();
  }

  return (
    <Box minH="100vh" className="bg-gray-50 dark:bg-gray-900">
      {/* Breadcrumb Navigation */}
      <Box className="bg-white dark:bg-gray-800 border-b border-gray-200 dark:border-gray-700">
        <Container size="4">
          <Flex align="center" gap="2" py="4">
            <Text size="2">
              <Link
                href="/"
                className="text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300"
              >
                Home
              </Link>
            </Text>
            <Text color="gray">/</Text>
            <Text size="2">
              <Link
                href="/primitives"
                className="text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300"
              >
                Primitives
              </Link>
            </Text>
            <Text color="gray">/</Text>
            <Text size="2" weight="medium">
              {component.name}
            </Text>
          </Flex>
        </Container>
      </Box>

      {/* Main Content */}
      <Container size="4" py="8">
        <PrimitiveDetail componentId={component.id} />
      </Container>
    </Box>
  );
}
