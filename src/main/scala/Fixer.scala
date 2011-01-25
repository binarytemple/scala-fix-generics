package eu.dnetlib.scalafixer

import com.google.inject._

import org.objectweb.asm._
import org.objectweb.asm.util._
import org.objectweb.asm.commons._
import java.io._
import org.apache.commons.io.IOUtils

/** Fixes a single class */
trait Fixer {
  def fix(clazz : InputStream) : Array[Byte]

  def fix(fileName : String) : Array[Byte] = fix(new FileInputStream(fileName))

  def fixAndSave(fileName : String, output : String): Unit = {
    val fixed = fix(fileName)
    IOUtils.copy(new ByteArrayInputStream(fixed), new FileOutputStream(output));
  }

}

trait SignatureFixer {
  val GenDecl = "^(<[^>]*>)(.*)".r

  def wrong(s: String): Boolean
  def fix(s: String): String
}

trait FixVisitor extends ClassVisitor

class FixVisitorImpl @Inject() (val signatureFixers: List[SignatureFixer], v: ClassVisitor) extends ClassAdapter(v)  with FixVisitor {

  override def visitMethod(access: Int, name: String, desc: String, signature: String, exceptions: Array[String]) =  {

    var sig = signature
    signatureFixers.foreach { f =>
      if(name != "<init>" && !verify(sig))
        sig = if(f.wrong(signature)) fix(name, f, signature) else sig
    }

    super.visitMethod(access, name, desc, sig, exceptions)
  }

  def fix(name: String, fixer: SignatureFixer, s: String): String = {
    println("FIXING method name: %s sig: %s".format(name, s))
    fixer.fix(s)
  }

  def verify(sig: String): Boolean = {
    if(sig == null)
      return true
    try {
      sun.reflect.generics.parser.SignatureParser.make().parseMethodSig(sig)
      true
    } catch {
      case _: java.lang.reflect.GenericSignatureFormatError => 
//        println("signature is invalid %s".format(sig))
        false
    }
  }
}

/**
 * Fix the generic declaration by defaulting to "extends Object"
 * Assumes that the scala compiler emits xx:Iyy:... instead of xx:Lclass;yy:...
 * The separator can be I or other chars, from what I saw...
 * */ 
class StupidSignatureFixer(val sep: String) extends SignatureFixer {

  /** check if the generic declaration appears to be truncated */
  def oldWrong(s: String): Boolean = {
    s match {
      case GenDecl(decl, _) => 
        decl.count(_ == ':') != decl.count(_ == ';')
      case _ => false
    }    
  }

  /** check if the generic declaration appears to be truncated */
  override def wrong(s: String): Boolean = oldWrong(s) && s.contains(":" + sep)

  override def fix(s: String): String = s.replaceAll(":" + sep, ":Ljava/lang/Object;")

}

/** This is another implementation using regexps, it could be more useful if we find other buggy generators */
class RegexpSignatureFixer extends SignatureFixer {
  /** check if the generic declaration appears to be truncated */
  override def wrong(s: String): Boolean = {
    s match {
      case GenDecl(decl, _) => 
        decl.count(_ == ':') != decl.count(_ == ';')
      case _ => false
    }    
  }

  override def fix(s: String) = {

    def enhance(m: String) = m match {
      case x if x.contains(":") => x; 
      case x => x+":Ljava/lang/Object"
    }

    s match {
      case GenDecl(decl, rest) => 
        decl.split(":I").map(enhance).mkString(";") + rest
    }
  }

}

/** if it's still invalid, then just suppress the signature */
class NullSignatureFixer extends SignatureFixer {
  override def wrong(s: String) = true
  override def fix(s: String) = {
    println("cannot fix %s, removing".format(s))
    null
  }
}

class FixerImpl @Inject() (val signatureFixers: List[SignatureFixer], val visitorFactory: (List[SignatureFixer], ClassVisitor) => FixVisitor) extends Fixer {
  def fix(clazz : InputStream) = {
    val reader = new ClassReader(clazz)
//    val flags = ClassWriter.COMPUTE_MAXS | ClassWriter.COMPU
    val flags = 0
    val writer = new ClassWriter(reader, flags)
    
    val vis = visitorFactory(signatureFixers, writer)

    reader.accept(vis, 0)
    writer.toByteArray()
  }
}

/** Fixes multiple classes */
trait MultiFixer {
  def fix(path : String)
}

class DirFixer @Inject() (val fixer: Fixer) extends MultiFixer {
  def fix(path : String) {
    import RichFile._

    val root = new File(path)
 
    // filtering comes for free
    for(f <- root.andTree; if f.getName.endsWith(".class")) 
      fixer.fixAndSave(f.getPath(), f.getPath())
  }
}

class JarFixer @Inject() (val fixer: Fixer) extends MultiFixer {
  def fix(path : String) {
  }
}
