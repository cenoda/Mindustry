import arc.files.Fi;
import arc.math.Mathf;
import arc.util.Time;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.core.GameState.State;
import mindustry.game.Schematics;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Tile;
import mindustry.world.Tiles;
import mindustry.world.blocks.power.ImpactReactor.ImpactReactorBuild;
import mindustry.world.blocks.production.AttributeCrafter;
import mindustry.type.Liquid;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cold-start fill at Time.delta=1 for reference schematics.
 * Same harness as Impact15Delta1FillTest (forced power.status, dome items).
 *
 * Witness (owner, 2026-09-16): PASS = there exists a tick T >= WITNESS_MIN_TICK
 * where every Impact has efficiency >= WITNESS_THRESHOLD and warmup >=
 * WITNESS_THRESHOLD. Checked every tick; the first such T is recorded.
 * No stock/collapse rule - spore/blast/cryo are diagnostics only.
 *
 * Clock: the per-tick loop mirrors the accounting part of Logic.update() (state.tick,
 * state.updateId, Time.update(), logicVars.update()) but runs its own building passes
 * instead of Logic.update()/updateEntities(). Time.updateGlobal() is never called.
 */
public class SchematicDelta1FillTest{

    static final String ROOT = "/home/cenoda/Documents/mindustry-slct/";

    /** Owner witness threshold, applied to both efficiency and warmup. */
    static final float WITNESS_THRESHOLD = 0.99f;
    /** Earliest tick that may count as a witness (excludes the cold-start transient). */
    static final int WITNESS_MIN_TICK = 60_000;

    @ParameterizedTest
    @CsvSource({
        "refs/schematics/15_impact_slct_784.msch, 15impact, 15",
        "refs/schematics/sirius-21-impact-1088-slct-v3.3.msch, sirius21, 21",
        "refs/schematics/qwerty-24-impact-1248-slct-v2.1.msch, qwerty24, 24",
        "refs/schematics/sirius-05-impact-272-slct-v1.5.msch, sirius05, 5",
        "refs/schematics/qwerty-12-impact-638-slct.msch, qwerty12, 12",
        "refs/schematics/sirius-07-impact-368-slct-v2.3.msch, sirius07, 7"
    })
    void coldStartFillDelta1(String rel, String tag, int expectIr) throws Exception{
        ApplicationTests.launchApplication();
        Time.setDeltaProvider(() -> 1f);
        Time.delta = 1f;

        logic.reset();
        Tiles tiles = world.resize(80, 80);
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

        // Mathf.rand is time-seeded and OverdriveBuild.charge = Mathf.random(reload) is drawn at
        // placement time, so the dome's boost phase - and therefore the whole fill run - varies
        // between JVMs. Seed it so a witness verdict is reproducible.
        Mathf.rand.setSeed(0L);

        var schem = Schematics.read(new Fi(ROOT + rel));
        Schematics.place(schem, 40, 40, Team.sharded, true);

        for(Tile t : world.tiles){
            if(t.build != null && t.isCenter()) t.build.updateProximity();
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
        assertTrue(nImpact == expectIr, tag + " impacts=" + nImpact);

        List<Building> irs = new ArrayList<>();
        for(Tile t : world.tiles){
            if(t.build != null && t.isCenter() && t.build.block == Blocks.impactReactor) irs.add(t.build);
        }

        int witnessTick = -1;
        float witnessMinEff = 0f, witnessMinWarm = 0f;

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"deltaPolicy\": \"Time.delta=1; state.tick+=1; state.updateId++; Time.update(); logicVars.update(); then controlled building.update() passes; power.status forced 1; dome items injected; Time.updateGlobal() not called\",\n");
        json.append("  \"floor\": \"darksand\",\n");
        json.append("  \"schematic\": \"").append(rel).append("\",\n");
        json.append(String.format(Locale.US,
            "  \"counts\": {\"impact\": %d, \"cultivator\": %d, \"cryoMixer\": %d, \"waterExtractor\": %d},\n",
            nImpact, nCult, nCryo, nWe));
        json.append("  \"samples\": [\n");
        int[] at = {0, 30_000, 100_000, 300_000, 600_000};
        int tick = 0;
        for(int i = 0; i < at.length; i++){
            while(tick < at[i]){
                // Mirror the clock/accounting part of Logic.update() without running the whole
                // logic update: the building passes below stay under this harness's control.
                // Time.updateGlobal() is deliberately NOT called - this is fixed-step headless
                // simulation and wall-clock/render-frame delta must not enter.
                Time.delta = 1f;
                state.tick += 1f;
                state.updateId++;
                Time.update();
                logicVars.update();

                for(Tile t : world.tiles){
                    if(t.build == null || !t.isCenter()) continue;
                    if(t.build.power != null) t.build.power.status = 1f;
                    if(t.build.block == Blocks.overdriveDome && t.build.items != null){
                        t.build.items.set(Items.phaseFabric, 20);
                        t.build.items.set(Items.silicon, 20);
                    }
                }
                for(Tile t : world.tiles){
                    if(t.build != null && t.isCenter()) t.build.update();
                }
                tick++;
                if(witnessTick < 0 && tick >= WITNESS_MIN_TICK){
                    float[] mm = minIr(irs);
                    if(mm[0] >= WITNESS_THRESHOLD && mm[1] >= WITNESS_THRESHOLD){
                        witnessTick = tick;
                        witnessMinEff = mm[0];
                        witnessMinWarm = mm[1];
                    }
                }
            }
            if(i > 0) json.append(",\n");
            json.append("    ").append(sample(tick, irs));
        }
        json.append("\n  ],\n");
        json.append(String.format(Locale.US,
            "  \"witness\": {\"rule\": \"exists T >= %d with min IR efficiency >= %.2f and min IR warmup >= %.2f; no stock rule\", "
                + "\"threshold\": %.2f, \"minTick\": %d, \"tick\": %d, \"minEff\": %.6f, \"minWarm\": %.6f, \"pass\": %s}\n",
            WITNESS_MIN_TICK, WITNESS_THRESHOLD, WITNESS_THRESHOLD,
            WITNESS_THRESHOLD, WITNESS_MIN_TICK, witnessTick,
            witnessTick < 0 ? 0 : witnessMinEff, witnessTick < 0 ? 0 : witnessMinWarm,
            witnessTick >= 0));
        json.append("}\n");

        Path out = Path.of(ROOT + "outputs/delta1-fill/" + tag + ".json");
        Files.createDirectories(out.getParent());
        Files.write(out, json.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(tag + "\n" + json);
    }

    static void zero(Building b){
        if(b.items != null) b.items.clear();
        if(b.liquids != null){
            for(Liquid l : content.liquids()) b.liquids.set(l, 0f);
        }
        if(b instanceof ImpactReactorBuild ir) ir.warmup = 0f;
        if(b instanceof AttributeCrafter.AttributeCrafterBuild ac){
            ac.progress = 0f;
            ac.warmup = 0f;
        }
        b.enabled = true;
    }

    /** {min efficiency, min warmup} over the tracked impact reactors. */
    static float[] minIr(List<Building> irs){
        float minEff = 1f, minWarm = 1f;
        for(Building b : irs){
            minEff = Math.min(minEff, b.efficiency);
            if(b instanceof ImpactReactorBuild ir) minWarm = Math.min(minWarm, ir.warmup);
        }
        return new float[]{irs.isEmpty() ? 0f : minEff, irs.isEmpty() ? 0f : minWarm};
    }

    static String sample(int tick, List<Building> irs){
        int nImp = 0, nEff1 = 0, nWarm1 = 0;
        double eff = 0, warm = 0, ts = 0;
        float blast = 0, spore = 0, cryo = 0, cryoCap = 0;
        float[] mm = minIr(irs);
        for(Tile t : world.tiles){
            if(t.build == null || !t.isCenter()) continue;
            Building b = t.build;
            if(b.items != null){
                spore += b.items.get(Items.sporePod);
                blast += b.items.get(Items.blastCompound);
            }
            if(b.liquids != null && (b.block == Blocks.impactReactor || b.block == Blocks.cryofluidMixer)){
                cryo += b.liquids.get(Liquids.cryofluid);
                cryoCap += b.block.liquidCapacity;
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
            }
        }
        return String.format(Locale.US,
            "{\"tick\": %d, \"nEff1\": %d, \"nWarm1\": %d, \"nImp\": %d, \"efficiencyMean\": %.6f, \"warmupMean\": %.6f, "
                + "\"minEff\": %.6f, \"minWarm\": %.6f, "
                + "\"timeScaleMean\": %.6f, \"blast\": %.1f, \"spore\": %.1f, \"cryo\": %.1f, \"cryoCap\": %.1f}",
            tick, nEff1, nWarm1, nImp, nImp == 0 ? 0 : eff / nImp, nImp == 0 ? 0 : warm / nImp,
            mm[0], mm[1],
            nImp == 0 ? 0 : ts / nImp, blast, spore, cryo, cryoCap);
    }
}
