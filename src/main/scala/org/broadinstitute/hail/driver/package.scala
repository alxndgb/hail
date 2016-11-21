package org.broadinstitute.hail

import java.io.File
import java.util
import java.util.Properties

import org.apache.log4j.{Level, LogManager, PropertyConfigurator}
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.{ProgressBarBuilder, SparkContext}
import org.broadinstitute.hail.utils._
import org.broadinstitute.hail.variant.VariantDataset

import scala.collection.JavaConverters._

package object driver {

  case class CountResult(nSamples: Int,
    nVariants: Long,
    nCalled: Option[Long]) {
    def nGenotypes: Long = nSamples * nVariants

    def callRate: Option[Double] =
      nCalled.flatMap(nCalled => divOption[Double](nCalled.toDouble * 100.0, nGenotypes))

    def toJavaMap: util.Map[String, Any] = {
      var m: Map[String, Any] = Map("nSamples" -> nSamples,
        "nVariants" -> nVariants,
        "nGenotypes" -> nGenotypes)
      nCalled.foreach { nCalled => m += "nCalled" -> nCalled }
      callRate.foreach { callRate => m += "callRate" -> callRate }
      m.asJava
    }
  }

  def count(vds: VariantDataset, countGenotypes: Boolean): CountResult = {
    val (nVariants, nCalled) =
      if (countGenotypes) {
        val (nVar, nCalled) = vds.rdd.map { case (v, (va, gs)) =>
          (1L, gs.count(_.isCalled).toLong)
        }.fold((0L, 0L)) { (comb, x) =>
          (comb._1 + x._1, comb._2 + x._2)
        }
        (nVar, Some(nCalled))
      } else
        (vds.nVariants, None)

    CountResult(vds.nSamples, nVariants, nCalled)
  }

  def configure(sc: SparkContext, logFile: String, quiet: Boolean, append: Boolean,
    parquetCompression: String, blockSize: Long, branchingFactor: Int, tmpDir: String) {
    require(blockSize > 0)
    require(branchingFactor > 0)

    val logProps = new Properties()
    if (quiet) {
      logProps.put("log4j.rootLogger", "OFF, stderr")
      logProps.put("log4j.appender.stderr", "org.apache.log4j.ConsoleAppender")
      logProps.put("log4j.appender.stderr.Target", "System.err")
      logProps.put("log4j.appender.stderr.threshold", "OFF")
      logProps.put("log4j.appender.stderr.layout", "org.apache.log4j.PatternLayout")
      logProps.put("log4j.appender.stderr.layout.ConversionPattern", "%d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n")
    } else {
      logProps.put("log4j.rootLogger", "INFO, logfile")
      logProps.put("log4j.appender.logfile", "org.apache.log4j.FileAppender")
      logProps.put("log4j.appender.logfile.append", append.toString)
      logProps.put("log4j.appender.logfile.file", logFile)
      logProps.put("log4j.appender.logfile.threshold", "INFO")
      logProps.put("log4j.appender.logfile.layout", "org.apache.log4j.PatternLayout")
      logProps.put("log4j.appender.logfile.layout.ConversionPattern", "%d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n")
    }

    LogManager.resetConfiguration()
    PropertyConfigurator.configure(logProps)

    val conf = sc.getConf

    conf.set("spark.ui.showConsoleProgress", "false")
    val progressBar = ProgressBarBuilder.build(sc)

    sc.hadoopConfiguration.set(
      "io.compression.codecs",
      "org.apache.hadoop.io.compress.DefaultCodec," +
        "org.broadinstitute.hail.io.compress.BGzipCodec," +
        "org.apache.hadoop.io.compress.GzipCodec")

    conf.set("spark.sql.parquet.compression.codec", parquetCompression)
    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")

    sc.hadoopConfiguration.setLong("mapreduce.input.fileinputformat.split.minsize", blockSize * 1024L * 1024L)

    /* `DataFrame.write` writes one file per partition.  Without this, read will split files larger than the default
     * parquet block size into multiple partitions.  This causes `OrderedRDD` to fail since the per-partition range
     * no longer line up with the RDD partitions.
     *
     * For reasons we don't understand, the DataFrame code uses `SparkHadoopUtil.get.conf` instead of the Hadoop
     * configuration in the SparkContext.  Set both for consistency.
     */
    SparkHadoopUtil.get.conf.setLong("parquet.block.size", 1099511627776L)
    sc.hadoopConfiguration.setLong("parquet.block.size", 1099511627776L)


    val jar = getClass.getProtectionDomain.getCodeSource.getLocation.toURI.getPath
    sc.addJar(jar)

    HailConfiguration.installDir = new File(jar).getParent + "/.."
    HailConfiguration.tmpDir = tmpDir
    HailConfiguration.branchingFactor = branchingFactor
  }
}