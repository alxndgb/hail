package is.hail.methods

import is.hail.annotations._
import is.hail.expr._
import is.hail.stats.InbreedingCombiner
import is.hail.utils._
import is.hail.variant.{Genotype, VariantDataset}

object ImputeSexPlink {

  def schema: Type = TStruct("isFemale" -> TBoolean,
    "Fstat" -> TFloat64,
    "nTotal" -> TInt64,
    "nCalled" -> TInt64,
    "expectedHoms" -> TFloat64,
    "observedHoms" -> TInt64)

  def determineSex(ibc: InbreedingCombiner, fFemaleThreshold: Double, fMaleThreshold: Double): Option[Boolean] = {
    ibc.Fstat
      .flatMap { x =>
        if (x < fFemaleThreshold)
          Some(true)
        else if (x > fMaleThreshold)
          Some(false)
        else None
      }
  }

  def apply(vds: VariantDataset,
    mafThreshold: Double,
    includePar: Boolean,
    fMaleThreshold: Double,
    fFemaleThreshold: Double,
    popFrequencyExpr: Option[String]): Map[Annotation, Annotation] = {

    val query = popFrequencyExpr.map { code =>
      val (t, f) = vds.queryVA(code)
      t match {
        case TFloat64 => f
        case other => fatal(s"invalid population frequency.  Expected Double, but got `$other'")
      }
    }

    vds.filterVariants { case (v, _, _) =>
      if (!includePar)
        v.inXNonPar
      else
        v.contig == "X" || v.contig == "23" || v.contig == "25"
    }
      .mapAnnotations { case (v, va, gs) =>
        query.map(_.apply(va))
          .getOrElse {
            var nAlt = 0
            var nTot = 0
            for (g <- gs) {
              if (g != null) {
                Genotype.nNonRefAlleles(g).foreach { c =>
                  nAlt += c
                  nTot += 2
                }
              }
            }
            if (nTot > 0)
              nAlt.toDouble / nTot
            else
              null
          }
      }
      .filterVariants { case (v, va, _) => Option(va).exists(_.asInstanceOf[Double] > mafThreshold) }
      .aggregateBySampleWithAll(new InbreedingCombiner)({ case (ibc, _, va, _, _, gt) =>
        ibc.merge(gt, va.asInstanceOf[Double])
      }, { case (ibc1, ibc2) =>
        ibc1.merge(ibc2)
      })
      .collect()
      .toMap
      .mapValues(ibc =>
        Annotation(determineSex(ibc, fFemaleThreshold, fMaleThreshold).orNull,
          ibc.Fstat.orNull,
          ibc.total,
          ibc.nCalled,
          ibc.expectedHoms,
          ibc.observedHoms))
  }
}
