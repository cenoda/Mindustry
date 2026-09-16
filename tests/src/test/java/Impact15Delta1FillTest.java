import arc.files.Fi;
import arc.struct.ObjectSet;
import arc.util.Time;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.core.GameState.State;
import mindustry.game.Schematics;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.world.Tile;
import mindustry.world.Tiles;
import mindustry.world.blocks.power.ImpactReactor.ImpactReactorBuild;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.production.AttributeCrafter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 15-impact schematic at Time.delta = 1: do internal stocks fill from a cold start?
 */
public class Impact15Delta1FillTest{

    static final String SCHEM = "/home/cenoda/Documents/mindustry-sltp/refs/schematics/15_impact_slct_784.msch";
    static final String OUT = "/home/cenoda/Documents/mindustry-sltp/outputs/15impact-delta1/result.json";

    @Test
    void coldStartFillDelta1() throws Exception{
        ApplicationTests.launchApplication();
        Time.setDeltaProvider(() -> 1f);
        Time.delta = 1f;

        logic.reset();
        Tiles tiles = world.resize(48, 48);
        world.beginMapLoad();
        tiles.fill();
        for(int x = 0; x < tiles.width; x++){
            for(int y = 0; y < tiles.height; y++){
                tiles.get(x, y).setFloor(Blocks.darksand.asFloor());
            }
        }
        world.endMapLoad();

        state.rules.infiniteResources = true;
        state.rules.canGameOver = false;
        state.rules.waves = false;
        state.rules.limitMapArea = false;
        state.set(State.playing);

        world.tile(2, 2).setBlock(Blocks.coreShard, Team.sharded, 0);

        var schem = Schematics.read(new Fi(SCHEM));
        assertTrue(schem.width == 28 && schem.height == 28, "unexpected schematic size " + schem.width + "x" + schem.height);
        Schematics.place(schem, 20, 20, Team.sharded, true);

        int nodes = 0, buildings = 0;
        Building linkNode = null;
        Tile psrc = null;
        for(Tile t : world.tiles){
            if(t.build != null && t.isCenter()) buildings++;
            if(t.build == null || !t.isCenter() || t.build.block != Blocks.powerNode) continue;
            nodes++;
            if(linkNode == null) linkNode = t.build;
        }
        assertTrue(nodes > 0, "no power nodes after place; buildings=" + buildings + " schemTiles=" + schem.tiles.size);
        int range = 10;
        outer:
        for(int x = 0; x < world.tiles.width; x++){
            for(int y = 0; y < world.tiles.height; y++){
                Tile n = world.tile(x, y);
                if(n == null || n.block() != Blocks.air) continue;
                for(Tile t : world.tiles){
                    if(t.build == null || !t.isCenter() || t.build.block != Blocks.powerNode) continue;
                    int dx = t.x - x, dy = t.y - y;
                    if(dx * dx + dy * dy <= range * range){
                        psrc = n;
                        linkNode = t.build;
                        break outer;
                    }
                }
            }
        }
        assertNotNull(psrc, "no air in range of a power node; nodes=" + nodes + " buildings=" + buildings);
        psrc.setBlock(Blocks.powerSource, Team.sharded, 0);
        psrc.build.configure(linkNode.pos());

        Building dome = find(Blocks.overdriveDome);
        assertNotNull(dome, "no overdrive dome");

        for(Tile t : world.tiles){
            if(t.build != null && t.isCenter()){
                t.build.updateProximity();
            }
        }

        int nImpact = 0, nCult = 0, nCryo = 0, nWe = 0;
        for(Tile t : world.tiles){
            if(t.build == null || !t.isCenter()) continue;
            Building b = t.build;
            if(b.block == Blocks.impactReactor) nImpact++;
            if(b.block == Blocks.cultivator) nCult++;
            if(b.block == Blocks.cryofluidMixer) nCryo++;
            if(b.block == Blocks.waterExtractor) nWe++;
            zero(b);
        }
        assertTrue(nImpact == 15, "impacts=" + nImpact);
        assertTrue(nCult == 11, "cults=" + nCult);
        assertTrue(nCryo == 19, "cryo=" + nCryo);
        assertTrue(nWe == 64, "we=" + nWe);

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"deltaPolicy\": \"Time.delta=1 each logic.update\",\n");
        json.append("  \"floor\": \"darksand\",\n");
        json.append("  \"schematic\": \"15_impact_slct_784.msch\",\n");
        json.append("  \"counts\": {\"impact\": 15, \"cultivator\": 11, \"cryoMixer\": 19, \"waterExtractor\": 64},\n");
        json.append("  \"samples\": [\n");
        int[] at = {0, 1000, 5000, 9000, 15000, 30000};
        int tick = 0;
        for(int i = 0; i < at.length; i++){
            int target = at[i];
            while(tick < target){
                Time.delta = 1f;
                Time.update();
                for(Tile t : world.tiles){
                    if(t.build == null || !t.isCenter()) continue;
                    if(t.build.power != null) t.build.power.status = 1f;
                    if(t.build.block == Blocks.overdriveDome && t.build.items != null){
                        t.build.items.set(Items.phaseFabric, 20);
                        t.build.items.set(Items.silicon, 20);
                    }
                }
                for(Tile t : world.tiles){
                    if(t.build != null && t.isCenter()){
                        t.build.update();
                    }
                }
                tick++;
            }
            if(i > 0) json.append(",\n");
            json.append("    ").append(sample(tick));
        }
        json.append("\n  ]\n}\n");

        Path out = Path.of(OUT);
        Files.createDirectories(out.getParent());
        Files.write(out, json.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(json);
    }

    static void zero(Building b){
        if(b.items != null) b.items.clear();
        if(b.liquids != null){
            for(Liquid l : content.liquids()){
                b.liquids.set(l, 0f);
            }
        }
        if(b instanceof ImpactReactorBuild ir){
            ir.warmup = 0f;
        }
        if(b instanceof AttributeCrafter.AttributeCrafterBuild ac){
            ac.progress = 0f;
            ac.warmup = 0f;
        }
        b.enabled = true;
    }

    static Building find(mindustry.world.Block block){
        for(Tile t : world.tiles){
            if(t.build != null && t.isCenter() && t.build.block == block) return t.build;
        }
        return null;
    }

    static Tile adjacentEmpty(Tile tile){
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for(int[] o : d){
            Tile t = world.tile(tile.x + o[0], tile.y + o[1]);
            if(t != null && t.block() == Blocks.air) return t;
        }
        return null;
    }

    static void placeTouchingSource(Building near, Item item){
        int size = near.block.size;
        int off = -(size - 1) / 2;
        Tile found = null;
        for(int dx = off - 1; dx <= off + size && found == null; dx++){
            for(int dy = off - 1; dy <= off + size; dy++){
                boolean edge = dx == off - 1 || dy == off - 1 || dx == off + size || dy == off + size;
                if(!edge) continue;
                Tile n = world.tile(near.tile.x + dx, near.tile.y + dy);
                if(n != null && n.block() == Blocks.air){
                    found = n;
                    break;
                }
            }
        }
        assertNotNull(found, "no air touching " + near.block);
        found.setBlock(Blocks.itemSource, Team.sharded, 0);
        found.build.configureAny(item);
    }

    static String sample(int tick){
        int nImp = 0, nEff1 = 0, nWarm1 = 0, cultOn = 0, nCult = 0;
        double eff = 0, warm = 0, ts = 0;
        float domePhase = 0, domeSi = 0;
        float blast = 0, blastCap = 0, cryo = 0, cryoCap = 0, water = 0, waterCap = 0, spore = 0;
        for(Tile t : world.tiles){
            if(t.build == null || !t.isCenter()) continue;
            Building b = t.build;
            if(b.items != null){
                spore += b.items.get(Items.sporePod);
                blast += b.items.get(Items.blastCompound);
            }
            if(b.liquids != null){
                water += b.liquids.get(Liquids.water);
                cryo += b.liquids.get(Liquids.cryofluid);
                waterCap += b.block.hasLiquids ? b.block.liquidCapacity : 0;
                if(b.block == Blocks.impactReactor || b.block == Blocks.cryofluidMixer){
                    cryoCap += b.block.liquidCapacity;
                }
            }
            if(b.block == Blocks.impactReactor){
                nImp++;
                eff += b.efficiency;
                ts += b.timeScale();
                if(b instanceof ImpactReactorBuild ir){
                    warm += ir.warmup;
                    if(ir.warmup >= 0.999f) nWarm1++;
                }
                if(b.efficiency >= 0.999f) nEff1++;
                blastCap += b.block.itemCapacity;
            }
            if(b.block == Blocks.overdriveDome && b.items != null){
                domePhase = b.items.get(Items.phaseFabric);
                domeSi = b.items.get(Items.silicon);
            }
            if(b.block == Blocks.cultivator){
                nCult++;
                if(b.enabled && b.efficiency > 0.5f) cultOn++;
            }
        }
        return String.format(Locale.US,
            "{\"tick\": %d, \"Time.time\": %.3f, \"groupsBuild\": %d, \"impacts\": %d, \"efficiencyMean\": %.6f, \"warmupMean\": %.6f, "
                + "\"nEff1\": %d, \"nWarm1\": %d, \"timeScaleMean\": %.6f, \"cultEnabledLike\": %d, \"nCult\": %d, "
                + "\"blast\": %.3f, \"blastCap\": %.3f, \"spore\": %.3f, \"water\": %.3f, \"waterCap\": %.3f, "
                + "\"cryo\": %.3f, \"cryoCap\": %.3f, \"domePhase\": %.1f, \"domeSilicon\": %.1f}",
            tick, Time.time, Groups.build.size(), nImp, nImp == 0 ? 0 : eff / nImp, nImp == 0 ? 0 : warm / nImp,
            nEff1, nWarm1, nImp == 0 ? 0 : ts / nImp, cultOn, nCult,
            blast, blastCap, spore, water, waterCap, cryo, cryoCap, domePhase, domeSi
        );
    }
}
