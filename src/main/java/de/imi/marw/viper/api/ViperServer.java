/* Copyright (c) 2017 Marius Wöste
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */
package de.imi.marw.viper.api;

import com.google.gson.Gson;
import de.imi.marw.viper.util.Util;
import de.imi.marw.viper.variants.VariantClusterBuilder;
import de.imi.marw.viper.variants.VariantTableCluster;
import de.imi.marw.viper.variants.table.CsvTableReader;
import de.imi.marw.viper.variants.table.VariantTable;
import de.imi.marw.viper.variants.table.ProgressManager;
import de.imi.marw.viper.visualization.IGVVisualizer;
import java.io.File;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import spark.Request;
import spark.Response;

import static spark.Spark.*;

/**
 *
 * @author marius
 */
public class ViperServer {

    private final ViperServerConfig config;
    private final Gson gson;
    private final VariantClusterBuilder clusterer;
    private final ProgressManager progressManager;
    private VariantTableCluster variantTableCluster;

    private IGVVisualizer igv;

    public ViperServer(ViperServerConfig config) {

        this.config = config;
        this.gson = new Gson();
        this.clusterer = new VariantClusterBuilder();
        this.progressManager = new ProgressManager(config.getWorkDir());
    }

    public void start() {

        this.igv = this.setupIGV();
        this.igv.start();

        this.variantTableCluster = this.loadVariants();

        this.igv.awaitStartup();

        this.setupRoutes();
    }

    private VariantTableCluster loadVariants() {
        CsvTableReader reader = new CsvTableReader(config.getAnalysisCsvFile(), config.getCsvDelimiter(), config.getPropertyCollectionDelimiter());

        VariantTable unclusteredTable = reader.readTable();
        VariantTableCluster cluster = clusterer.clusterVariantTable(unclusteredTable);

        progressManager.loadProgress(cluster);

        return cluster;
    }

    private void setupRoutes() {
        ipAddress("127.0.0.1");
        port(config.getViperPort());

        staticFiles.externalLocation("public");
        staticFiles.externalLocation(this.config.getWorkDir());

        setupTableApi();

        init();
    }

    private void setupTableApi() {

        get("/api/variant-table/size", (req, res) -> variantTableCluster.getClusteredTable().getNumberOfCalls(), gson::toJson);

        get("/api/variant-table/row", (req, res) -> {

            int queryIndex = gson.fromJson(req.queryParams("index"), Integer.class);

            return variantTableCluster.getClusteredTable().getCall(queryIndex);

        }, gson::toJson);

        get("/api/variant-table/rows", (req, res) -> {

            int from = gson.fromJson(req.queryParams("from"), Integer.class);
            int to = gson.fromJson(req.queryParams("to"), Integer.class);

            return variantTableCluster.getClusteredTable().getCallRange(from, to);

        }, gson::toJson);

        get("/api/variant-table/column-names", (req, res) -> variantTableCluster.getClusteredTable().getColumnNames(), gson::toJson);

        put("/api/variant-table/decision", (req, res) -> {

            int queryIndex = gson.fromJson(req.queryParams("index"), Integer.class);
            String decision = req.queryParams("decision");

            variantTableCluster.getClusteredTable().setCallProperty(queryIndex, VariantTable.DECISION_COLUMN_NAME, decision);

            return "OK";
        });

        get("/api/variant-table/related-calls", (req, res) -> {

            int queryIndex = gson.fromJson(req.queryParams("index"), Integer.class);

            return variantTableCluster.getUnclusteredCalls(queryIndex);

        }, gson::toJson);

        get("/api/variant-table/related-calls/column-names", (req, res) -> variantTableCluster.getUnclusteredTable().getColumnNames(), gson::toJson);

        post("/api/variant-table/snapshot", this::takeSnapshot);
        get("/api/variant-table/is-snapshot-available", (req, res) -> {

            String key = req.queryParams("key");

            return igv.isSnapshotDone(key);

        }, gson::toJson);

        get("/api/variant-table/snapshot/:key", (req, res) -> {

            String key = req.params("key");

            res.raw().setContentType("image/png");

            File image = new File(this.config.getWorkDir() + "/" + key + ".png");

            try (OutputStream out = res.raw().getOutputStream()) {
                ImageIO.write(ImageIO.read(image), "png", out);
            }

            return res.raw();

        });

        post("/api/variant-table/save", (req, res) -> {

            boolean success = progressManager.saveProgress(variantTableCluster);

            if (success) {
                return "OK";
            } else {
                res.status(500);
                return "ERROR";
            }
        });
    }

    private Object takeSnapshot(Request req, Response res) {
        int queryIndex = gson.fromJson(req.queryParams("index"), Integer.class);
        int maxIndex = Util.clamp(queryIndex + 10, 0, variantTableCluster.getClusteredTable().getNumberOfCalls());

        for (int i = queryIndex; queryIndex < maxIndex; queryIndex++) {

            List<Map<String, Object>> relatedCalls = variantTableCluster.getUnclusteredCalls(queryIndex);

            Map<String, Object> firstCall = relatedCalls.get(0);

            String sample = firstCall.get(VariantTable.SAMPLE_COLUMN_NAME).toString();

            String chr1 = firstCall.get(VariantTable.CHR1_COLUMN_NAME).toString();
            String chr2 = firstCall.get(VariantTable.CHR2_COLUMN_NAME).toString();

            int bp1 = ((Double) firstCall.get(VariantTable.BP1_COLUMN_NAME)).intValue();
            int bp2 = ((Double) firstCall.get(VariantTable.BP2_COLUMN_NAME)).intValue();

            this.igv.scheduleSnapshot(sample, chr1, bp1);
            this.igv.scheduleSnapshot(sample, chr2, bp2);
        }

        return "OK";
    }

    private IGVVisualizer setupIGV() {
        new File(this.config.getWorkDir()).mkdirs();

        return new IGVVisualizer(this.config.getIgvJar(),
                this.config.getFastaRef(),
                this.config.getIgvPort(),
                this.config.getWorkDir(),
                this.config.getBamDir());
    }
}
