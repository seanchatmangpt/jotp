'use client';

import { useState, useEffect } from 'react';
import { JOTPComponent } from '@/lib/data/jotp-architecture';
import { Card, Badge, Flex, Box, Heading, Text } from '@radix-ui/themes';

interface ApiReferenceProps {
  componentId: string;
}

interface Method {
  name: string;
  signature: string;
  description: string;
  returnType: string;
  parameters: Parameter[];
  isStatic?: boolean;
}

interface Parameter {
  name: string;
  type: string;
  description?: string;
}

export function ApiReference({ componentId }: ApiReferenceProps) {
  const [methods, setMethods] = useState<Method[]>([]);

  useEffect(() => {
    // Simulate loading methods - in real app, this would come from API
    // For now, we'll generate placeholder methods based on component
    const mockMethods: Record<string, Method[]> = {
      proc: [
        {
          name: 'spawn',
          signature: 'spawn(initial: S, handler: BiFunction<S, M, S>): Proc<S, M>',
          description: 'Static factory: create and start a process (mirrors spawn/3)',
          returnType: 'Proc<S, M>',
          parameters: [
            { name: 'initial', type: 'S', description: 'Initial state' },
            { name: 'handler', type: 'BiFunction<S, M, S>', description: 'Pure state handler' }
          ],
          isStatic: true
        },
        {
          name: 'tell',
          signature: 'tell(msg: M): void',
          description: 'Fire-and-forget message send (non-blocking)',
          returnType: 'void',
          parameters: [
            { name: 'msg', type: 'M', description: 'Message to send' }
          ]
        },
        {
          name: 'ask',
          signature: 'ask(msg: M, timeout: Duration): CompletableFuture<S>',
          description: 'Request-reply with custom timeout (gen_server:call)',
          returnType: 'CompletableFuture<S>',
          parameters: [
            { name: 'msg', type: 'M', description: 'Message to send' },
            { name: 'timeout', type: 'Duration', description: 'Timeout duration' }
          ]
        },
        {
          name: 'trapExits',
          signature: 'trapExits(trap: boolean): void',
          description: 'Enable/disable exit signal trapping',
          returnType: 'void',
          parameters: [
            { name: 'trap', type: 'boolean', description: 'True to trap exits as messages' }
          ]
        },
        {
          name: 'stop',
          signature: 'stop(): void',
          description: 'Gracefully stop the process',
          returnType: 'void',
          parameters: []
        }
      ],
      supervisor: [
        {
          name: 'create',
          signature: 'create(strategy: Strategy, maxRestarts: int, window: Duration): Supervisor',
          description: 'Create supervisor with restart limits',
          returnType: 'Supervisor',
          parameters: [
            { name: 'strategy', type: 'Strategy', description: 'ONE_FOR_ONE | ONE_FOR_ALL | REST_FOR_ONE' },
            { name: 'maxRestarts', type: 'int', description: 'Max restarts in window' },
            { name: 'window', type: 'Duration', description: 'Time window' }
          ],
          isStatic: true
        },
        {
          name: 'startChild',
          signature: 'startChild(spec: ChildSpec<S,M>): ProcRef<S,M>',
          description: 'Add child with explicit ChildSpec',
          returnType: 'ProcRef<S,M>',
          parameters: [
            { name: 'spec', type: 'ChildSpec<S,M>', description: 'Child specification' }
          ]
        },
        {
          name: 'terminateChild',
          signature: 'terminateChild(id: String): void',
          description: 'Stop a child and retain its spec',
          returnType: 'void',
          parameters: [
            { name: 'id', type: 'String', description: 'Child ID to stop' }
          ]
        },
        {
          name: 'whichChildren',
          signature: 'whichChildren(): List<ChildInfo>',
          description: 'Snapshot of current child tree state',
          returnType: 'List<ChildInfo>',
          parameters: []
        }
      ],
      'state-machine': [
        {
          name: 'of',
          signature: 'of(initialState: S, initialData: D, fn: TransitionFn<S,E,D>): StateMachine<S,E,D>',
          description: 'Create and start state machine',
          returnType: 'StateMachine<S,E,D>',
          parameters: [
            { name: 'initialState', type: 'S', description: 'Initial state' },
            { name: 'initialData', type: 'D', description: 'Initial data' },
            { name: 'fn', type: 'TransitionFn<S,E,D>', description: 'Transition function' }
          ],
          isStatic: true
        },
        {
          name: 'send',
          signature: 'send(event: E): void',
          description: 'Fire-and-forget event delivery',
          returnType: 'void',
          parameters: [
            { name: 'event', type: 'E', description: 'Event to send' }
          ]
        },
        {
          name: 'call',
          signature: 'call(event: E): CompletableFuture<D>',
          description: 'Request-reply event delivery',
          returnType: 'CompletableFuture<D>',
          parameters: [
            { name: 'event', type: 'E', description: 'Event to send' }
          ]
        }
      ]
    };

    setMethods(mockMethods[componentId] || []);
  }, [componentId]);

  if (methods.length === 0) {
    return (
      <Card>
        <Flex direction="column" gap="4" p="6">
          <Heading size="5">API Reference</Heading>
          <Text color="gray" size="2">Complete method reference</Text>
          <Flex align="center" justify="center" py="8">
            <Text color="gray">API documentation coming soon...</Text>
          </Flex>
        </Flex>
      </Card>
    );
  }

  return (
    <Card>
      <Flex direction="column" gap="4" p="6">
        <Heading size="5">API Reference</Heading>
        <Text color="gray" size="2">Complete method reference with parameters and return types</Text>

        <Flex direction="column" gap="6">
          {methods.map((method, index) => (
            <Box key={index} pb="6" style={{ borderBottom: index < methods.length - 1 ? '1px solid var(--gray-6)' : 'none' }}>
              <Flex justify="between" align="start" mb="2">
                <Heading size="4">{method.name}</Heading>
                {method.isStatic && (
                  <Badge color="gray">static</Badge>
                )}
              </Flex>

              <Box mb="3" p="3" style={{ backgroundColor: 'var(--gray-3)', borderRadius: '0.5rem', overflowX: 'auto' }}>
                <Text size="2" style={{ color: 'var(--green-9)', fontFamily: 'monospace' }}>
                  {method.signature}
                </Text>
              </Box>

              <Text color="gray" size="2" mb="3">
                {method.description}
              </Text>

              <Flex direction="row" gap="4">
                {/* Return Type */}
                <Box>
                  <Text weight="bold" color="gray" mb="2" style={{ textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                    Returns
                  </Text>
                  <Box p="2" style={{ backgroundColor: 'var(--gray-3)', borderRadius: '0.25rem' }}>
                    <Text size="2" style={{ color: 'var(--blue-9)', fontFamily: 'monospace' }}>
                      {method.returnType}
                    </Text>
                  </Box>
                </Box>

                {/* Parameters */}
                {method.parameters.length > 0 && (
                  <Box>
                    <Text weight="bold" color="gray" mb="2" style={{ textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                      Parameters
                    </Text>
                    <Box style={{ backgroundColor: 'var(--gray-3)', borderRadius: '0.5rem', padding: '12px' }}>
                      <Flex direction="column" gap="2">
                        {method.parameters.map((param, paramIndex) => (
                          <Flex key={paramIndex} gap="4" align="start">
                            <Text style={{ width: '30%', fontFamily: 'monospace', fontSize: '12px' }}>
                              {param.name}
                            </Text>
                            <Box style={{ width: '40%' }}>
                              <Box p="1" style={{ backgroundColor: 'var(--gray-1)', borderRadius: '0.25rem' }}>
                                <Text style={{ color: 'var(--blue-9)', fontFamily: 'monospace' }}>
                                  {param.type}
                                </Text>
                              </Box>
                            </Box>
                            <Text size="2" color="gray" style={{ flex: 1 }}>
                              {param.description || '-'}
                            </Text>
                          </Flex>
                        ))}
                      </Flex>
                    </Box>
                  </Box>
                )}
              </Flex>
            </Box>
          ))}
        </Flex>
      </Flex>
    </Card>
  );
}
