package edu.kit.ipd.pronat.runner;

import edu.kit.ipd.parse.luna.data.MissingDataException;
import edu.kit.ipd.parse.luna.graph.IGraph;
import edu.kit.ipd.parse.luna.pipeline.PipelineStageException;
import edu.kit.ipd.pronat.action_analyzer.ActionAnalyzer;
import edu.kit.ipd.pronat.ast_synth.ASTSynthStage;
import edu.kit.ipd.pronat.babelfy_wsd.Wsd;
import edu.kit.ipd.pronat.code_gen.CodeGenStage;
import edu.kit.ipd.pronat.code_injector.CodeInjectorStage;
import edu.kit.ipd.pronat.concurrency.ConcurrencyAgent;
import edu.kit.ipd.pronat.condition_detection.ConditionDetector;
import edu.kit.ipd.pronat.context.ContextAnalyzer;
import edu.kit.ipd.pronat.coref.CorefAnalyzer;
import edu.kit.ipd.pronat.graph_builder.GraphBuilder;
import edu.kit.ipd.pronat.loop.LoopDetectionAgent;
import edu.kit.ipd.pronat.multiasr.MultiASRPipelineStage;
import edu.kit.ipd.pronat.ner.NERTagger;
import edu.kit.ipd.pronat.postpipelinedatamodel.PostPipelineData;
import edu.kit.ipd.pronat.prepipedatamodel.PrePipelineData;
import edu.kit.ipd.pronat.prepipedatamodel.tools.StringToHypothesis;
import edu.kit.ipd.pronat.shallow_nlp.ShallowNLP;
import edu.kit.ipd.pronat.srl.SRLabeler;
import edu.kit.ipd.pronat.teaching.TeachingDetector;
import edu.kit.ipd.pronat.vamos.MethodSynthesizer;
import edu.kit.pronat.ipd.ast_extractor.ASTExtractorStage;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PronatRunner {

  private static final Logger logger = LoggerFactory.getLogger(PronatRunner.class);
  private static final String CMD_TEXT_S = "t";
  private static final String CMD_TEXT_L = "text";
  private static final String CMD_FILE_S = "f";
  private static final String CMD_FILE_L = "file";

  MultiASRPipelineStage multiASR;
  ShallowNLP shallowNLP;
  NERTagger nerTagger;
  SRLabeler srLabeler;
  GraphBuilder graphBuilder;

  Wsd wsd;
  ContextAnalyzer contextAnalyzer;
  CorefAnalyzer corefAnalyzer;
  ConditionDetector conditionDetector;
  MyLoopDetectionAgent loopDetectionAgent;
  MyConcurrencyAgent concurrencyAgent;
  MyTeachingDetector teachingDetector;
  MethodSynthesizer methodSynthesizer;
  MyActionRecognizer actionRecognizer;

  ASTSynthStage astSynthStage;
  ASTExtractorStage astExtractorStage;
  CodeGenStage codeGenStage;
  CodeInjectorStage codeInjectorStage;

  public static void main(String[] args) {

    long start;
    CommandLine cmd = null;
    //command line parsing
    try {
      cmd = doCommandLineParsing(args);
    } catch (final ParseException exception) {
      System.err.println("Wrong command line arguments given: " + exception.getMessage());
      System.exit(1);
    }
    if(!(cmd.hasOption(CMD_FILE_S) || cmd.hasOption(CMD_TEXT_S))) {
      logger.error("Please choose either mode 'text' or 'file' and provide either a string or a file path.");
    }

    PronatRunner pronatRunner = new PronatRunner();

    PrePipelineData prePipelineData = new PrePipelineData();
    PostPipelineData postPipelineData = new PostPipelineData();

    if(cmd.hasOption(CMD_TEXT_S)) {
      pronatRunner.setHypFromStringInPPD(cmd.getOptionValue(CMD_TEXT_S), prePipelineData);
      pronatRunner.initPreNoASR();
    }

    if(cmd.hasOption(CMD_FILE_S)) {
      pronatRunner.setAudioinPPD(cmd.getOptionValue(CMD_FILE_S), prePipelineData);
      pronatRunner.initPreAll();
    }

    pronatRunner.initAgents();
    pronatRunner.initPost();

    start = System.currentTimeMillis();
    try {
      if(cmd.hasOption(CMD_TEXT_S)) {
        pronatRunner.runPreNoASR(prePipelineData);
      }
      if(cmd.hasOption(CMD_FILE_S)) {
        pronatRunner.runPreAll(prePipelineData);
      }

    } catch (PipelineStageException e) {
      e.printStackTrace();
    }

    IGraph agentresult = null;
    try {
      agentresult = pronatRunner.runAgents(prePipelineData.getGraph());
    } catch (MissingDataException e) {
      e.printStackTrace();
    }

    postPipelineData.setGraph(agentresult);
    try {
      pronatRunner.runPost(postPipelineData);
    } catch (PipelineStageException e) {
      e.printStackTrace();
    }
    logger.info("Runtime in ms: {}", (System.currentTimeMillis() - start));
  }

  private void setHypFromStringInPPD(String input, PrePipelineData prePipelineData) {
    prePipelineData.setMainHypothesis(StringToHypothesis.stringToMainHypothesis(input, true));
    try {
      logger.info("Main Hypothesis: {}", prePipelineData.getMainHypothesis().toString());
    } catch (MissingDataException e) {
      e.printStackTrace();
    }
  }

  private void setAudioinPPD(String path, PrePipelineData prePipelineData) {
    prePipelineData.setInputFilePath(Paths.get(path));
  }

  private void initPreAll() {
    multiASR = new MultiASRPipelineStage();
    shallowNLP = new ShallowNLP();
    nerTagger = new NERTagger();
    srLabeler = new SRLabeler();
    graphBuilder = new GraphBuilder();
    multiASR.init();
    shallowNLP.init();
    nerTagger.init();
    srLabeler.init();
    graphBuilder.init();
  }

  private void initPreNoASR() {
    shallowNLP = new ShallowNLP();
    nerTagger = new NERTagger();
    srLabeler = new SRLabeler();
    graphBuilder = new GraphBuilder();
    shallowNLP.init();
    nerTagger.init();
    srLabeler.init();
    graphBuilder.init();
  }

  private void initAgents() {
    wsd = new Wsd();
    contextAnalyzer = new ContextAnalyzer();
    corefAnalyzer = new CorefAnalyzer();
    conditionDetector = new ConditionDetector();
    loopDetectionAgent = new MyLoopDetectionAgent();
    concurrencyAgent = new MyConcurrencyAgent();
    teachingDetector = new MyTeachingDetector();
    methodSynthesizer = new MethodSynthesizer();
    actionRecognizer = new MyActionRecognizer();
    wsd.init();
    contextAnalyzer.init();
    corefAnalyzer.init();
    conditionDetector.init();
    loopDetectionAgent.init();
    concurrencyAgent.init();
    teachingDetector.init();
    methodSynthesizer.init();
    actionRecognizer.init();
  }

  private void initPost() {
    astSynthStage = new ASTSynthStage();
    astExtractorStage = new ASTExtractorStage();
    codeGenStage = new CodeGenStage();
    codeInjectorStage = new CodeInjectorStage();
    astSynthStage.init();
    astExtractorStage.init();
    codeGenStage.init();
    codeInjectorStage.init();
  }

  private void runPreAll(PrePipelineData prePipelineData) throws PipelineStageException {
    multiASR.exec(prePipelineData);
    runPreNoASR(prePipelineData);
  }

  private void runPreNoASR(PrePipelineData prePipelineData) throws PipelineStageException {
    shallowNLP.exec(prePipelineData);
    nerTagger.exec(prePipelineData);
    srLabeler.exec(prePipelineData);
    graphBuilder.exec(prePipelineData);
  }

  private IGraph runAgents(IGraph graph) {
    wsd.setGraph(graph);
    wsd.exec();
    actionRecognizer.setGraph(wsd.getGraph());
    actionRecognizer.exec();
    teachingDetector.setGraph(actionRecognizer.getGraph());
    teachingDetector.exec();
    contextAnalyzer.setGraph(teachingDetector.getGraph());
    contextAnalyzer.exec();
    corefAnalyzer.setGraph(contextAnalyzer.getGraph());
    corefAnalyzer.exec();
    conditionDetector.setGraph(corefAnalyzer.getGraph());
    conditionDetector.exec();
    loopDetectionAgent.setGraph(conditionDetector.getGraph());
    loopDetectionAgent.exec();
    concurrencyAgent.setGraph(loopDetectionAgent.getGraph());
    concurrencyAgent.exec();
    methodSynthesizer.setGraph(concurrencyAgent.getGraph());
    methodSynthesizer.exec();
    return methodSynthesizer.getGraph();
  }

  private void runPost(PostPipelineData postPipelineData) throws PipelineStageException {
    astSynthStage.exec(postPipelineData);
    astExtractorStage.exec(postPipelineData);
    codeGenStage.exec(postPipelineData);
    codeInjectorStage.exec(postPipelineData);
  }

  static CommandLine doCommandLineParsing(String[] args) throws ParseException {
    CommandLine line;
    final Options options = new Options();
    final Option text;
    final Option file;

    text = new Option(CMD_TEXT_S, CMD_TEXT_L, true, "Input text in quotes.");
    text.setRequired(false);
    text.setType(String.class);

    file = new Option(CMD_FILE_S, CMD_FILE_L, true, "Path to input audio file (flac)");
    file.setRequired(false);
    file.setType(Path.class);

    options.addOption(text);
    options.addOption(file);

    // create the parser
    final CommandLineParser parser = new DefaultParser();

    line = parser.parse(options, args);

    return line;
  }

  class MyActionRecognizer extends ActionAnalyzer {
    @Override
    public void exec() {
      super.exec();
    }
  }

  class MyTeachingDetector extends TeachingDetector {
    @Override
    public void exec() {
      super.exec();
    }
  }

  class MyLoopDetectionAgent extends LoopDetectionAgent {
    @Override
    public void exec() {
      super.exec();
    }
  }

  class MyConcurrencyAgent extends ConcurrencyAgent {
    @Override
    public void exec() {
      super.exec();
    }
  }

}
