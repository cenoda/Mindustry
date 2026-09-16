import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.core.GameState.State;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Tile;
import mindustry.world.Tiles;
import mindustry.world.blocks.production.AttributeCrafter.AttributeCrafterBuild;
import mindustry.world.meta.Attribute;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated cultivator spore measurement vs displayed 60/craftTime rate.
 * Does not use the 15-impact schematic or processors.
 */
public class CultivatorSporeMeasureTest{

    static final int WARMUP_TICKS = 3_000;
    static final int WINDOW_TICKS = 60_000;
    static final float DISPLAYED_1X = 60f / 100f;

    @Test
    void measureSporeRates() throws Exception{
        ApplicationTests.launchApplication();
        Time.setDeltaProvider(() -> 1f);

        String run1 = runScenario(false, false);
        String run2 = runScenario(true, false);
        String run3 = runScenario(false, true);

        Path outDir = Path.of("/home/cenoda/Documents/mindustry-sltp/outputs/spore-measure");
        Files.createDirectories(outDir);
        String json = "{\n" + run1 + ",\n" + run2 + ",\n" + run3 + ",\n  \"deltaPolicy\": \"Time.setDeltaProvider(() -> 1f); Time.delta is 1 each logic.update()\",\n  \"displayed1xSource\": \"60 / craftTime(100) = 0.6\",\n  \"floor\": \"darksand\",\n  \"tickPath\": \"logic.update()\"\n}\n";
        Files.write(outDir.resolve("result.json"), json.getBytes(StandardCharsets.UTF_8));
        System.out.println(json);
    }

    String runScenario(boolean overdrive, boolean forcedBoost){
        logic.reset();
        Time.setDeltaProvider(() -> 1f);

        Tiles tiles = world.resize(24, 24);
        world.beginMapLoad();
        tiles.fill();
        for(int x = 0; x < tiles.width; x++){
            for(int y = 0; y < tiles.height; y++){
                tiles.get(x, y).setFloor(Blocks.darksand.asFloor());
            }
        }
        world.endMapLoad();

        state.rules.infiniteResources = false;
        state.rules.env = Vars.defaultEnv;
        state.rules.limitMapArea = false;
        state.rules.canGameOver = false;
        state.rules.waves = false;
        state.set(State.playing);

        // Core far from cultivator so it does not steal adjacency from void.
        world.tile(2, 2).setBlock(Blocks.coreShard, Team.sharded, 0);

        // Cultivator 2x2 centered at (10,10) covering [9,10]x[9,10].
        world.tile(10, 10).setBlock(Blocks.cultivator, Team.sharded, 0);
        Building cult = world.tile(10, 10).build;
        assertNotNull(cult);

        // Size-2 cultivator occupies [10,11]x[10,11]. Neighbors must touch that square.
        world.tile(9, 10).setBlock(Blocks.liquidSource, Team.sharded, 0);
        world.tile(9, 10).build.configureAny(Liquids.water);
        world.tile(12, 10).setBlock(Blocks.powerSource, Team.sharded, 0);
        world.tile(12, 10).build.configure(world.tile(10, 10).pos());
        world.tile(10, 9).setBlock(Blocks.itemVoid, Team.sharded, 0);
        world.tile(11, 12).setBlock(Blocks.itemVoid, Team.sharded, 0);

        if(overdrive){
            // Dome 3x3 at (18,10), range 200 covers cultivator.
            world.tile(18, 10).setBlock(Blocks.overdriveDome, Team.sharded, 0);
            world.tile(16, 10).setBlock(Blocks.powerSource, Team.sharded, 0);
            world.tile(18, 8).setBlock(Blocks.itemSource, Team.sharded, 0);
            world.tile(18, 8).build.configureAny(Items.phaseFabric);
            world.tile(18, 12).setBlock(Blocks.itemSource, Team.sharded, 0);
            world.tile(18, 12).build.configureAny(Items.silicon);
        }

        for(Tile t : world.tiles){
            if(t.build != null && t.isCenter()){
                t.build.updateProximity();
            }
        }
        cult.onProximityUpdate();

        float floorSpore = 0f, floorWater = 0f;
        for(int dx = 0; dx < 2; dx++){
            for(int dy = 0; dy < 2; dy++){
                var f = world.tile(10 + dx, 10 + dy).floor();
                floorSpore += f.attributes.get(Attribute.spores);
                floorWater += f.attributes.get(Attribute.water);
            }
        }

        tick(WARMUP_TICKS, forcedBoost);
        float prevProgress = ((AttributeCrafterBuild)cult).progress;
        float time0 = Time.time;
        double tick0 = state.tick;
        float ts0 = cult.timeScale();
        float eff0 = cult.efficiency;

        Window w1 = measureWindow(cult, WINDOW_TICKS, 0, prevProgress, forcedBoost);
        Window w2 = measureWindow(cult, WINDOW_TICKS, w1.craftsEnd, w1.progressEnd, forcedBoost);

        float ts = cult.timeScale();
        float displayed = DISPLAYED_1X * ts;
        float seconds1 = w1.dt / 60f;
        float seconds2 = w2.dt / 60f;
        float rate1 = w1.crafts / seconds1;
        float rate2 = w2.crafts / seconds2;

        String name = forcedBoost ? "forced2p5" : (overdrive ? "overdriveDome" : "base1x");
        return String.format(Locale.US,
            "  \"%s\": {\n" +
            "    \"overdriveDome\": %s,\n" +
            "    \"forcedApplyBoostEveryTick\": %s,\n" +
            "    \"timeScaleMeasured\": %.9f,\n" +
            "    \"timeScaleWarmup\": %.9f,\n" +
            "    \"efficiency\": %.9f,\n" +
            "    \"efficiencyWarmup\": %.9f,\n" +
            "    \"attrsum\": %.9f,\n" +
            "    \"efficiencyMultiplier\": %.9f,\n" +
            "    \"floorSporeSum2x2\": %.9f,\n" +
            "    \"floorWaterSum2x2\": %.9f,\n" +
            "    \"sporeEnv\": %.9f,\n" +
            "    \"displayedItemsPerSec\": %.12f,\n" +
            "    \"window1\": {\"ticks\": %d, \"Time.timeDelta\": %.6f, \"state.tickDelta\": %.6f, \"crafts\": %d, \"itemsOnCultivatorDelta\": %d, \"itemsPerSec\": %.12f, \"ratioVsDisplayed\": %.12f, \"tsMin\": %.9f, \"tsMax\": %.9f, \"tsMean\": %.12f, \"effMin\": %.9f, \"waterMin\": %.6f, \"progressIncSum\": %.6f, \"progressEnd\": %.9f},\n" +
            "    \"window2\": {\"ticks\": %d, \"Time.timeDelta\": %.6f, \"state.tickDelta\": %.6f, \"crafts\": %d, \"itemsOnCultivatorDelta\": %d, \"itemsPerSec\": %.12f, \"ratioVsDisplayed\": %.12f, \"tsMin\": %.9f, \"tsMax\": %.9f, \"tsMean\": %.12f, \"effMin\": %.9f, \"waterMin\": %.6f, \"progressIncSum\": %.6f, \"progressEnd\": %.9f},\n" +
            "    \"combinedItemsPerSec\": %.12f,\n" +
            "    \"combinedRatio\": %.12f,\n" +
            "    \"warmupTicks\": %d,\n" +
            "    \"Time.timeAfterWarmup\": %.6f,\n" +
            "    \"state.tickAfterWarmup\": %.6f\n" +
            "  }",
            name,
            overdrive,
            forcedBoost,
            ts, ts0, cult.efficiency, eff0,
            ((AttributeCrafterBuild)cult).attrsum,
            ((AttributeCrafterBuild)cult).efficiencyMultiplier(),
            floorSpore, floorWater, Attribute.spores.env(),
            displayed,
            WINDOW_TICKS, w1.dt, w1.st, w1.crafts, w1.itemDelta, rate1, rate1 / displayed, w1.tsMin, w1.tsMax, w1.tsSum / WINDOW_TICKS, w1.effMin, w1.waterMin, w1.progressIncSum, w1.progressEnd,
            WINDOW_TICKS, w2.dt, w2.st, w2.crafts, w2.itemDelta, rate2, rate2 / displayed, w2.tsMin, w2.tsMax, w2.tsSum / WINDOW_TICKS, w2.effMin, w2.waterMin, w2.progressIncSum, w2.progressEnd,
            (w1.crafts + w2.crafts) / ((w1.dt + w2.dt) / 60f),
            ((w1.crafts + w2.crafts) / ((w1.dt + w2.dt) / 60f)) / displayed,
            WARMUP_TICKS, time0, tick0
        );
    }

    static class Window{
        int crafts;
        int craftsEnd;
        float progressEnd;
        float dt;
        float st;
        int itemDelta;
        float tsMin = Float.POSITIVE_INFINITY;
        float tsMax;
        double tsSum;
        double progressIncSum;
        float effMin = Float.POSITIVE_INFINITY;
        float waterMin = Float.POSITIVE_INFINITY;
    }

    Window measureWindow(Building cult, int ticks, int craftsCarry, float prevProgress, boolean forcedBoost){
        AttributeCrafterBuild c = (AttributeCrafterBuild)cult;
        int items0 = cult.items.get(Items.sporePod);
        float t0 = Time.time;
        double s0 = state.tick;
        int crafts = 0;
        float prev = prevProgress;
        Window w = new Window();
        for(int i = 0; i < ticks; i++){
            cult.liquids.set(Liquids.water, cult.block.liquidCapacity);
            if(forcedBoost){
                cult.applyBoost(2.5f, 10f);
            }
            float ts = cult.timeScale();
            w.tsMin = Math.min(w.tsMin, ts);
            w.tsMax = Math.max(w.tsMax, ts);
            w.tsSum += ts;
            w.effMin = Math.min(w.effMin, cult.efficiency);
            w.waterMin = Math.min(w.waterMin, cult.liquids.get(Liquids.water));
            logic.update();
            float p = c.progress;
            if(p + 1e-6f < prev){
                crafts++;
                w.progressIncSum += (1f - prev) + p;
            }else{
                w.progressIncSum += p - prev;
            }
            prev = p;
        }
        w.crafts = crafts;
        w.craftsEnd = craftsCarry + crafts;
        w.progressEnd = prev;
        w.dt = Time.time - t0;
        w.st = (float)(state.tick - s0);
        w.itemDelta = cult.items.get(Items.sporePod) - items0;
        return w;
    }

    void tick(int n, boolean forcedBoost){
        Building cult = world.tile(10, 10).build;
        for(int i = 0; i < n; i++){
            if(cult != null){
                cult.liquids.set(Liquids.water, cult.block.liquidCapacity);
                if(forcedBoost){
                    cult.applyBoost(2.5f, 10f);
                }
            }
            logic.update();
        }
    }
}
