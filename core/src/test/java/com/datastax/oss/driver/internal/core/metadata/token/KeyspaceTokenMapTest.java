package com.datastax.oss.driver.internal.core.metadata.token;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.token.Token;
import com.datastax.oss.driver.api.core.metadata.token.TokenRange;
import com.datastax.oss.driver.internal.core.context.InternalDriverContext;
import com.datastax.oss.driver.internal.core.metadata.DefaultNode;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableList;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableMap;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableSet;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class KeyspaceTokenMapTest {
    private static final String DC1 = "DC1";
    private static final String DC2 = "DC2";
    private static final String RACK1 = "RACK1";
    private static final String RACK2 = "RACK2";

    private static final CqlIdentifier KS1 = CqlIdentifier.fromInternal("ks1");
    private static final CqlIdentifier KS2 = CqlIdentifier.fromInternal("ks2");

    private static final TokenFactory TOKEN_FACTORY = new Murmur3TokenFactory();

    private static final String TOKEN1 = "-9000000000000000000";
    private static final String TOKEN2 = "-6000000000000000000";
    private static final String TOKEN3 = "4000000000000000000";
    private static final String TOKEN4 = "9000000000000000000";

    private static final ImmutableMap<String, String> REPLICATE_ON_BOTH_DCS =
            ImmutableMap.of(
                    "class", "org.apache.cassandra.locator.NetworkTopologyStrategy", DC1, "1", DC2, "1");
    private static final ImmutableMap<String, String> REPLICATE_ON_DC1 =
            ImmutableMap.of("class", "org.apache.cassandra.locator.NetworkTopologyStrategy", DC1, "1");
    @Mock
    private InternalDriverContext context;
    private ReplicationStrategyFactory replicationStrategyFactory;

    @Before
    public void setup() {
        replicationStrategyFactory = new DefaultReplicationStrategyFactory(context);
    }
    @Test
    public void should_build_token_map() {
        // Given
        Node node1 = mockNode(DC1, RACK1, ImmutableSet.of(TOKEN1));
        Node node2 = mockNode(DC2, RACK2, ImmutableSet.of(TOKEN2));
        Node node3 = mockNode(DC1, RACK1, ImmutableSet.of(TOKEN3));
        Node node4 = mockNode(DC2, RACK2, ImmutableSet.of(TOKEN4));
        List<Node> nodes = ImmutableList.of(node1, node2, node3, node4);
        List<KeyspaceMetadata> keyspaces =
                ImmutableList.of(
                        mockKeyspace(KS1, REPLICATE_ON_BOTH_DCS), mockKeyspace(KS2, REPLICATE_ON_DC1));
        Map<String, String> replicationConfig = keyspaces.get(0).getReplication();
        Map<Token, Node> tokenToPrimary =
                ImmutableMap.<Token, Node>builder()
                        .put(TOKEN_FACTORY.parse(TOKEN1), node1)
                        .put(TOKEN_FACTORY.parse(TOKEN2), node2)
                        .put(TOKEN_FACTORY.parse(TOKEN3), node3)
                        .put(TOKEN_FACTORY.parse(TOKEN4), node4)
                        .build();
        List<Token> ring = ImmutableList.of(TOKEN_FACTORY.parse(TOKEN1), TOKEN_FACTORY.parse(TOKEN2),
                TOKEN_FACTORY.parse(TOKEN3), TOKEN_FACTORY.parse(TOKEN4));

        ImmutableSet.Builder<TokenRange> builder = ImmutableSet.builder();
        for (int i = 0; i < ring.size(); i++) {
            Token start = ring.get(i);
            Token end = ring.get((i + 1) % ring.size());
            builder.add(TOKEN_FACTORY.range(start, end));
        }
        Set<TokenRange> tokenRanges = builder.build();
        // When
        KeyspaceTokenMap tokenMap = KeyspaceTokenMap.build(replicationConfig, tokenToPrimary, ring, tokenRanges, TOKEN_FACTORY, replicationStrategyFactory, "test");
    }
    private DefaultNode mockNode(String dc, String rack, Set<String> tokens) {
        DefaultNode node = mock(DefaultNode.class);
        when(node.getDatacenter()).thenReturn(dc);
        when(node.getRack()).thenReturn(rack);
//        when(node.getRawTokens()).thenReturn(tokens);
        return node;
    }

    private KeyspaceMetadata mockKeyspace(CqlIdentifier name, Map<String, String> replicationConfig) {
        KeyspaceMetadata keyspace = mock(KeyspaceMetadata.class);
//        when(keyspace.getName()).thenReturn(name);
        when(keyspace.getReplication()).thenReturn(replicationConfig);
        return keyspace;
    }
}
