/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
package neo4j.org.testkit.backend.messages.responses.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;

import org.neo4j.driver.Value;
import org.neo4j.driver.internal.value.MapValue;

import static neo4j.org.testkit.backend.messages.responses.serializer.GenUtils.cypherObject;
import static neo4j.org.testkit.backend.messages.responses.serializer.GenUtils.object;

public class TestkitMapValueSerializer extends StdSerializer<MapValue>
{
    public TestkitMapValueSerializer()
    {
        super( MapValue.class );
    }

    @Override
    public void serialize( MapValue mapValue, JsonGenerator gen, SerializerProvider serializerProvider ) throws IOException
    {
        cypherObject( gen, "CypherMap", () ->
        {
            gen.writeFieldName( "value" );
            object( gen, () ->
            {
                for ( Map.Entry<String,Value> entry : mapValue.asMap( Function.identity() ).entrySet() )
                {
                    gen.writeObjectField( entry.getKey(), entry.getValue() );
                }
            } );
        } );
    }
}