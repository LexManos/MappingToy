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

import java.net.URL;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraftforge.srgutils.MinecraftVersion;

public class ManifestJson {
    public static final String MOJANG_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    public Map<String, String> latest;
    public List<Entry> versions;
    private Map<MinecraftVersion, Entry> entry_map;

    public static class Entry {
        public String id;
        public String type;
        public Date time;
        public Date releaseTime;
        public URL url;
    }

    public Map<MinecraftVersion, Entry> getEntries() {
        if (entry_map == null && versions != null) {
            entry_map = new HashMap<>();
            for (Entry e : versions) {
                if ("release".equals(e.type) || "snapshot".equals(e.type)) {
                    try {
                        entry_map.put(MinecraftVersion.from(e.id), e);
                    } catch (Throwable t) {
                        // Most likely a april fools version not following spec, ignore it.
                    }
                }
            }
        }
        return entry_map;
    }

    public Entry getVersion(MinecraftVersion version) {
        return getEntries().get(version);
    }
}
