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
import java.util.Random;
import java.util.function.Supplier;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated cultivator spore rate with client-like Time.delta (not forced 1).
 * Does not use the 15-impact schematic.
 */
public class CultivatorSporeVariableDeltaTest{

    static final int WARMUP_UPDATES = 3_000;
    static final int WINDOW_UPDATES = 60_000;
    static final float DISPLAYED_1X = 60f / 100f;
    static final float MAX_DELTA_CLIENT = 4f;

    static final float[] deltaBox = {1f};

    @Test
    void measureVariableDeltaSporeRates() throws Exception{
        ApplicationTests.launchApplication();
        Time.setDeltaProvider(() -> {
            float d = deltaBox[0];
            if(Float.isNaN(d) || Float.isInfinite(d)) return 1f;
            return Math.max(0.0001f, Math.min(d, MAX_DELTA_CLIENT));
        });

        String jitter1x = runScenario("jitter1x", false, clientJitter(1));
        String jitter2p5 = runScenario("jitterForced2p5", true, clientJitter(2));
        String const05 = runScenario("const0p5", false, () -> 0.5f);
        String const17 = runScenario("const1p7", false, () -> 1.7f);
        String const40 = runScenario("const4p0", false, () -> 4.0f);

        Path outDir = Path.of("/home/cenoda/Documents/mindustry-sltp/outputs/spore-measure");
        Files.createDirectories(outDir);
        String json = "{\n" + jitter1x + ",\n" + jitter2p5 + ",\n" + const05 + ",\n" + const17 + ",\n" + const40 + ",\n"
            + "  \"deltaPolicy\": \"Time.delta assigned each logic.update() (client clamp 0.0001..4). Time.updateGlobal is not called by Logic.update, so setDeltaProvider alone does not change Time.delta.\",\n"
            + "  \"displayed1xSource\": \"60 / craftTime(100) = 0.6\",\n"
            + "  \"floor\": \"darksand\",\n"
            + "  \"tickPath\": \"logic.update()\",\n"
            + "  \"rateDefinition\": \"crafts / (Time.time/60) game-seconds\"\n}\n";
        Files.write(outDir.resolve("result-variable-delta.json"), json.getBytes(StandardCharsets.UTF_8));
        System.out.println(json);
    }

    static Supplier<Float> clientJitter(long seed){
        Random rng = new Random(seed);
        return () -> {
            float u = rng.nextFloat();
            float d;
            if(u < 0.80f){
                d = 0.92f + rng.nextFloat() * 0.16f;
            }else if(u < 0.95f){
                d = 1.15f + rng.nextFloat() * 0.65f;
            }else{
                d = 2.0f + rng.nextFloat() * 2.0f;
            }
            return Math.min(d, MAX_DELTA_CLIENT);
        };
    }

    String runScenario(String name, boolean forcedBoost, Supplier<Float> deltas){
        logic.reset();
        deltaBox[0] = 1f;

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

        world.tile(2, 2).setBlock(Blocks.coreShard, Team.sharded, 0);
        world.tile(10, 10).setBlock(Blocks.cultivator, Team.sharded, 0);
        Building cult = world.tile(10, 10).build;
        assertNotNull(cult);

        world.tile(9, 10).setBlock(Blocks.liquidSource, Team.sharded, 0);
        world.tile(9, 10).build.configureAny(Liquids.water);
        world.tile(12, 10).setBlock(Blocks.powerSource, Team.sharded, 0);
        world.tile(12, 10).build.configure(world.tile(10, 10).pos());
        world.tile(10, 9).setBlock(Blocks.itemVoid, Team.sharded, 0);
        world.tile(11, 12).setBlock(Blocks.itemVoid, Team.sharded, 0);

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

        tick(WARMUP_UPDATES, forcedBoost, deltas);
        float prevProgress = ((AttributeCrafterBuild)cult).progress;
        Window w1 = measureWindow(cult, WINDOW_UPDATES, prevProgress, forcedBoost, deltas);
        Window w2 = measureWindow(cult, WINDOW_UPDATES, w1.progressEnd, forcedBoost, deltas);

        float ts = cult.timeScale();
        float displayed = DISPLAYED_1X * ts;
        float rate1 = w1.crafts / (w1.dt / 60f);
        float rate2 = w2.crafts / (w2.dt / 60f);
        float combined = (w1.crafts + w2.crafts) / ((w1.dt + w2.dt) / 60f);

        return String.format(Locale.US,
            "  \"%s\": {\n" +
            "    \"forcedApplyBoostEveryTick\": %s,\n" +
            "    \"timeScaleEnd\": %.9f,\n" +
            "    \"efficiencyEnd\": %.9f,\n" +
            "    \"floorSporeSum2x2\": %.9f,\n" +
            "    \"floorWaterSum2x2\": %.9f,\n" +
            "    \"sporeEnv\": %.9f,\n" +
            "    \"displayedItemsPerSec\": %.12f,\n" +
            "    \"window1\": %s,\n" +
            "    \"window2\": %s,\n" +
            "    \"combinedItemsPerSec\": %.12f,\n" +
            "    \"combinedRatio\": %.12f\n" +
            "  }",
            name, forcedBoost, ts, cult.efficiency, floorSpore, floorWater, Attribute.spores.env(), displayed,
            windowJson(w1, rate1, displayed),
            windowJson(w2, rate2, displayed),
            combined, combined / displayed
        );
    }

    String windowJson(Window w, float rate, float displayed){
        return String.format(Locale.US,
            "{\"updates\": %d, \"Time.timeDelta\": %.6f, \"crafts\": %d, \"itemsPerGameSec\": %.12f, \"ratioVsDisplayed\": %.12f, "
                + "\"deltaMin\": %.6f, \"deltaMax\": %.6f, \"deltaMean\": %.9f, \"tsMin\": %.9f, \"tsMax\": %.9f, \"tsMean\": %.12f, "
                + "\"effMin\": %.9f, \"waterMin\": %.6f, \"progressEnd\": %.9f}",
            WINDOW_UPDATES, w.dt, w.crafts, rate, rate / displayed,
            w.dMin, w.dMax, w.dSum / WINDOW_UPDATES, w.tsMin, w.tsMax, w.tsSum / WINDOW_UPDATES,
            w.effMin, w.waterMin, w.progressEnd
        );
    }

    static class Window{
        int crafts;
        float progressEnd;
        float dt;
        float dMin = Float.POSITIVE_INFINITY, dMax, dSum;
        float tsMin = Float.POSITIVE_INFINITY, tsMax;
        double tsSum;
        float effMin = Float.POSITIVE_INFINITY;
        float waterMin = Float.POSITIVE_INFINITY;
    }

    Window measureWindow(Building cult, int updates, float prevProgress, boolean forcedBoost, Supplier<Float> deltas){
        AttributeCrafterBuild c = (AttributeCrafterBuild)cult;
        float t0 = Time.time;
        int crafts = 0;
        float prev = prevProgress;
        Window w = new Window();
        for(int i = 0; i < updates; i++){
            float d = deltas.get();
            deltaBox[0] = d;
            Time.delta = d;
            w.dMin = Math.min(w.dMin, d);
            w.dMax = Math.max(w.dMax, d);
            w.dSum += d;
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
            if(p + 1e-5f < prev){
                crafts++;
            }
            prev = p;
        }
        w.crafts = crafts;
        w.progressEnd = prev;
        w.dt = Time.time - t0;
        return w;
    }

    void tick(int n, boolean forcedBoost, Supplier<Float> deltas){
        Building cult = world.tile(10, 10).build;
        for(int i = 0; i < n; i++){
            float d = deltas.get();
            deltaBox[0] = d;
            Time.delta = d;
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
