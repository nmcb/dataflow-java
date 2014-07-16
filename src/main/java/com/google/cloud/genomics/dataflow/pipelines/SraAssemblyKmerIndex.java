/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.genomics.dataflow.pipelines;

import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.runners.Description;
import com.google.cloud.dataflow.sdk.runners.PipelineRunner;
import com.google.cloud.dataflow.sdk.transforms.*;
import com.google.cloud.dataflow.sdk.util.GcsUtil;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.utils.OptionsParser;
import com.google.cloud.dataflow.utils.RequiredOption;
import com.google.cloud.genomics.dataflow.functions.ExtractContigs;
import com.google.cloud.genomics.dataflow.functions.AssembleSra;
import com.google.cloud.genomics.dataflow.functions.GenerateKmers;
import com.google.cloud.genomics.dataflow.functions.WriteKmers;
import com.google.cloud.genomics.dataflow.utils.GcsFileUtil;
import com.google.cloud.genomics.dataflow.utils.GenomicsOptions;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Dataflow piepline for constructing kmer indicies from assembled genomes using a list of SRA 
 * accessions. SRA accessions can be given either as a local or GCS based txt file.
 * 
 * If the writeContigs option is set, assembled contigs will be uploaded to GCS under the directory
 * outDir/contigs/<Accession>.fasta. In any subsequent runs of the pipeline, before committing to
 * perform assembly the pipeline will check that directory to see if the contigs were already
 * generated, in which case it will just use those (unless forceAssembly flag is set).
 * 
 * Needed utilities are stored under the publicly available GCS bucket gs://genomics-utilities/
 * and will be staged to all workers during processing.
 */
public class SraAssemblyKmerIndex {
  private static final Logger LOG = Logger.getLogger(SraAssemblyKmerIndex.class.getName());

  // Do not instantiate
  private SraAssemblyKmerIndex() { }

  private static class Options extends GenomicsOptions {
    @Description("Path of GCS directory to write results to")
    @RequiredOption
    public String outputLocation;
    
    @Description("Path to list of SRA accessions")
    @RequiredOption
    public String sraFile;
    
    @Description("Whether or not kmer indices should be printed as table instead or entries")
    public boolean writeTable;
    
    @Description("Prefix to be used for output file. Files written will be in the form"
        + "<outputPrefix>K<KValue>.csv/txt. Default value is KmerIndex")
    public String outputPrefix = "KmerIndex";
    
    @Description("Whether or not to output contigs to GCS\n"
        + "If set, contigs will be print to outDir/contigs\n"
        + "Otherwise, they will be put under stagingLocation")
    public boolean outputContigs;
    
    @Description("Length threshold for filtering contigs."
        + "All contigs shorter or equal than this will be ignored.")
    public int lengthThreshold = Integer.MAX_VALUE;
    
    @Description("Coverage threshold for filtering contigs."
        + "All contigs with coverage less than this will be ignored.")
    public double coverageThreshold = Double.MAX_VALUE;
    
    @Description("If set, all accessions will be assembled even if the assembled contig exists")
    public boolean forceAssembly;
    
    @Description("K values to be used for indexing. Separate multiple values using commas\n"
        + "EG: --kValues=1,2,3")
    @RequiredOption
    public String kValues;
    
    private int[] parsedValues;
    
    public void checkArgs() {
      try {
        for (int val : parseValues()) {
          if (val < 1 || val > 256) {
            LOG.severe("K values must be between 1 and 256");
            throw new IllegalArgumentException("K value out of bounds");
          }
        }
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid K values");
      }
      
      // Local file is also supported
      if (!sraFile.startsWith("gs://")) {
        try {
          String newFile = stagingLocation + "/TempSraFile.txt";
          GcsFileUtil.localToGcs(sraFile, newFile, GcsUtil.create(this), "text/plain", 4096);
        } catch (IOException e) {
          LOG.severe("Could not stage list of SRA accessions to GCS");
          throw new IllegalArgumentException("Error loading SRA file");
        }
      }
    }
    
    public int[] parseValues() throws NumberFormatException {
      if (parsedValues != null) {
        return parsedValues;
      }
      
      String[] values = kValues.split(",");
      parsedValues = new int[values.length];
      for (int i = 0; i < values.length; i++) {
        parsedValues[i] = Integer.parseInt(values[i]);
      }
      
      return parsedValues;
    }
  }
  
  public static void main(String[] args) {
    Options options = OptionsParser.parse(
        args, Options.class, SraAssemblyKmerIndex.class.getSimpleName());
    options.checkArgs();
    
    int[] kValues = options.parseValues();
    
    LOG.info("Starting pipeline...");
    Pipeline p = Pipeline.create();
    
    // Do we need to do the sharding workaround here?
    PCollection<String> accessions = p.begin().apply(TextIO.Read.from(options.sraFile));
    
    PCollection<KV<String, String>> contigs = accessions.apply(
        ParDo.named("Assemble Contigs").of(new AssembleSra(
            (options.outputContigs) ? options.outputLocation: options.stagingLocation,
                options.forceAssembly)))
        .apply(ParDo.named("Extract Contigs").of(
            new ExtractContigs(options.lengthThreshold, options.coverageThreshold)));
    
    for (int kValue : kValues) {
      String outFile = options.outputLocation + "/" + options.outputPrefix + "K" + kValue;
      outFile += (options.writeTable) ? ".csv" : ".txt";
      contigs.apply(ParDo.named("Generate Kmers")
          .of(new GenerateKmers(kValue)))
          .apply(new WriteKmers(outFile, options.writeTable));
    }
    
    p.run(PipelineRunner.fromOptions(options));
  }
}