/*
 * MappingToy
 * Copyright (c) 2019
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package net.minecraftforge.lex.mappingtoy;

import java.lang.reflect.Type;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;

public class DateTypeAdapter implements JsonSerializer<Date>, JsonDeserializer<Date> {
    private static final DateFormat US_FORMAT = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, Locale.US);
    private static final DateFormat ISO8601_FORMAT = new SimpleDateFormat("yyyy-MM-dd\'T\'HH:mm:ssZ");

    @Override
    public Date deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (!json.isJsonPrimitive() || !((JsonPrimitive)json).isString())
            throw new JsonParseException("Date needs to be a string: " + json.getAsString());
        if (typeOfT != Date.class)
            throw new JsonParseException(getClass() + " can only deserialze to Date, not " + typeOfT.getTypeName());

        String data = json.getAsString();
        try {
            return US_FORMAT.parse(data);
        } catch (ParseException e) {}

        try {
            return ISO8601_FORMAT.parse(data);
        } catch (ParseException e) {}

        try {
            String tmp = data.replaceAll("Z", "+00:00");
            tmp = tmp.substring(0, 22) + tmp.substring(23);
            return ISO8601_FORMAT.parse(tmp);
        } catch (ParseException e) {
            throw new JsonSyntaxException("Invalid date: " + data, e);
        }
    }

    @Override
    public JsonElement serialize(Date src, Type typeOfSrc, JsonSerializationContext context) {
        String ret = ISO8601_FORMAT.format(src);
        return new JsonPrimitive(ret.substring(0, 22) + ':' + ret.substring(22));
    }
}
