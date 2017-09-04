/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.driver.v1.integration;

import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.neo4j.driver.internal.netty.ListenableFuture;
import org.neo4j.driver.internal.netty.StatementResultCursor;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.exceptions.ClientException;
import org.neo4j.driver.v1.exceptions.ServiceUnavailableException;
import org.neo4j.driver.v1.types.Node;
import org.neo4j.driver.v1.util.TestNeo4j;
import org.neo4j.driver.v1.util.TestNeo4jSession;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.neo4j.driver.internal.util.Iterables.single;
import static org.neo4j.driver.v1.Values.parameters;
import static org.neo4j.driver.v1.util.TestUtil.await;
import static org.neo4j.driver.v1.util.TestUtil.awaitAll;
import static org.neo4j.driver.v1.util.TestUtil.get;

public class SessionAsyncIT
{
    @Rule
    public TestNeo4jSession session = new TestNeo4jSession();

    @Test
    public void shouldRunQueryWithEmptyResult()
    {
        StatementResultCursor cursor = session.runAsync( "CREATE (:Person)" );

        assertThat( await( cursor.fetchAsync() ), is( false ) );
    }

    @Test
    public void shouldRunQueryWithSingleResult()
    {
        StatementResultCursor cursor = session.runAsync( "CREATE (p:Person {name: 'Nick Fury'}) RETURN p" );

        assertThat( await( cursor.fetchAsync() ), is( true ) );

        Record record = cursor.current();
        Node node = record.get( 0 ).asNode();
        assertEquals( "Person", single( node.labels() ) );
        assertEquals( "Nick Fury", node.get( "name" ).asString() );

        assertThat( await( cursor.fetchAsync() ), is( false ) );
    }

    @Test
    public void shouldRunQueryWithMultipleResults()
    {
        StatementResultCursor cursor = session.runAsync( "UNWIND [1,2,3] AS x RETURN x" );

        assertThat( await( cursor.fetchAsync() ), is( true ) );
        assertEquals( 1, cursor.current().get( 0 ).asInt() );

        assertThat( await( cursor.fetchAsync() ), is( true ) );
        assertEquals( 2, cursor.current().get( 0 ).asInt() );

        assertThat( await( cursor.fetchAsync() ), is( true ) );
        assertEquals( 3, cursor.current().get( 0 ).asInt() );

        assertThat( await( cursor.fetchAsync() ), is( false ) );
    }

    @Test
    public void shouldFailForIncorrectQuery()
    {
        StatementResultCursor cursor = session.runAsync( "RETURN" );

        try
        {
            await( cursor.fetchAsync() );
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertSyntaxError( e );
        }
    }

    @Test
    public void shouldFailWhenQueryFailsAtRuntime()
    {
        StatementResultCursor cursor = session.runAsync( "UNWIND [1, 2, 0] AS x RETURN 10 / x" );

        assertThat( await( cursor.fetchAsync() ), is( true ) );
        assertEquals( 10, cursor.current().get( 0 ).asInt() );

        assertThat( await( cursor.fetchAsync() ), is( true ) );
        assertEquals( 5, cursor.current().get( 0 ).asInt() );

        try
        {
            await( cursor.fetchAsync() );
            System.out.println( cursor.current() );
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertArithmeticError( e );
        }
    }

    @Test
    public void shouldFailWhenServerIsRestarted() throws Exception
    {
        StatementResultCursor cursor = session.runAsync(
                "UNWIND range(0, 1000000) AS x " +
                "CREATE (n1:Node {value: x})-[r:LINKED {value: x}]->(n2:Node {value: x}) " +
                "DETACH DELETE n1, n2 " +
                "RETURN x" );

        try
        {
            ListenableFuture<Boolean> recordAvailable = cursor.fetchAsync();

            // kill db after receiving the first record
            // do it from a listener so that event loop thread executes the kill operation
            recordAvailable.addListener( new KillDbListener( session ) );

            while ( await( recordAvailable ) )
            {
                assertNotNull( cursor.current() );
                recordAvailable = cursor.fetchAsync();
            }
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( ServiceUnavailableException.class ) );
        }
    }

    @Test
    public void shouldAllowNestedQueries()
    {
        StatementResultCursor cursor = session.runAsync( "UNWIND [1, 2, 3] AS x CREATE (p:Person {id: x}) RETURN p" );

        Future<List<Future<Boolean>>> queriesExecuted = runNestedQueries( cursor );
        List<Future<Boolean>> futures = await( queriesExecuted );

        List<Boolean> futureResults = awaitAll( futures );
        assertEquals( 7, futureResults.size() );

        StatementResultCursor personCursor = session.runAsync( "MATCH (p:Person) RETURN p ORDER BY p.id" );

        List<Node> personNodes = new ArrayList<>();
        while ( await( personCursor.fetchAsync() ) )
        {
            personNodes.add( personCursor.current().get( 0 ).asNode() );
        }
        assertEquals( 3, personNodes.size() );

        Node node1 = personNodes.get( 0 );
        assertEquals( 1, node1.get( "id" ).asInt() );
        assertEquals( 10, node1.get( "age" ).asInt() );

        Node node2 = personNodes.get( 1 );
        assertEquals( 2, node2.get( "id" ).asInt() );
        assertEquals( 20, node2.get( "age" ).asInt() );

        Node node3 = personNodes.get( 2 );
        assertEquals( 3, node3.get( "id" ).asInt() );
        assertEquals( 30, personNodes.get( 2 ).get( "age" ).asInt() );
    }

    private Future<List<Future<Boolean>>> runNestedQueries( StatementResultCursor inputCursor )
    {
        Promise<List<Future<Boolean>>> resultPromise = GlobalEventExecutor.INSTANCE.newPromise();
        runNestedQueries( inputCursor, new ArrayList<Future<Boolean>>(), resultPromise );
        return resultPromise;
    }

    private void runNestedQueries( final StatementResultCursor inputCursor, final List<Future<Boolean>> futures,
            final Promise<List<Future<Boolean>>> resultPromise )
    {
        final ListenableFuture<Boolean> inputAvailable = inputCursor.fetchAsync();
        futures.add( inputAvailable );

        inputAvailable.addListener( new Runnable()
        {
            @Override
            public void run()
            {
                if ( get( inputAvailable ) )
                {
                    Record record = inputCursor.current();
                    Node node = record.get( 0 ).asNode();
                    long id = node.get( "id" ).asLong();
                    long age = id * 10;

                    StatementResultCursor updatedCursor =
                            session.runAsync( "MATCH (p:Person {id: $id}) SET p.age = $age RETURN p",
                                    parameters( "id", id, "age", age ) );

                    futures.add( updatedCursor.fetchAsync() );

                    runNestedQueries( inputCursor, futures, resultPromise );
                }
                else
                {
                    resultPromise.setSuccess( futures );
                }
            }
        } );
    }

    private static void assertSyntaxError( Exception e )
    {
        assertThat( e, instanceOf( ClientException.class ) );
        assertThat( ((ClientException) e).code(), containsString( "SyntaxError" ) );
        assertThat( e.getMessage(), startsWith( "Unexpected end of input" ) );
    }

    private static void assertArithmeticError( Exception e )
    {
        assertThat( e, instanceOf( ClientException.class ) );
        assertThat( ((ClientException) e).code(), containsString( "ArithmeticError" ) );
    }

    private static class KillDbListener implements Runnable
    {

        final TestNeo4j neo4j;
        final AtomicBoolean shouldKillDb = new AtomicBoolean( true );

        KillDbListener( TestNeo4j neo4j )
        {
            this.neo4j = neo4j;
        }

        @Override
        public void run()
        {
            if ( shouldKillDb.get() )
            {
                killDb();
                shouldKillDb.set( false );
            }
        }

        void killDb()
        {
            try
            {
                neo4j.killDb();
            }
            catch ( IOException e )
            {
                throw new RuntimeException( e );
            }
        }
    }
}