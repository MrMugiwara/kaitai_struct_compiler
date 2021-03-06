package io.kaitai.struct

import java.io.FileReader

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.kaitai.struct.exprlang.DataType._
import io.kaitai.struct.format._
import io.kaitai.struct.languages._
import io.kaitai.struct.languages.components.{LanguageCompiler, LanguageCompilerStatic}

import scala.collection.mutable.ListBuffer

class ClassCompiler(val topClass: ClassSpec, val lang: LanguageCompiler) extends AbstractCompiler {
  val topClassName = List(topClass.meta.get.id)

  topClass.name = topClassName

  def markupClassNames(curClass: ClassSpec): Unit = {
    curClass.types.foreach { case (nestedName: String, nestedClass) =>
      nestedClass.name = curClass.name ::: List(nestedName)
      nestedClass.upClass = Some(curClass)
      markupClassNames(nestedClass)
    }
  }

  def resolveUserTypes(curClass: ClassSpec): Unit = {
    curClass.seq.foreach((attr) => resolveUserTypeForAttr(curClass, attr))
    curClass.instances.foreach { case (instName, instSpec) =>
      instSpec match {
        case pis: ParseInstanceSpec =>
          resolveUserTypeForAttr(curClass, pis)
        case _: ValueInstanceSpec =>
          // ignore all other types of instances
      }
    }

    curClass.types.foreach { case (_, nestedClass) =>
      resolveUserTypes(nestedClass)
    }
  }

  def resolveUserTypeForAttr(curClass: ClassSpec, attr: AttrLikeSpec): Unit =
    resolveUserType(curClass, attr.dataType)

  def resolveUserType(curClass: ClassSpec, dataType: BaseType): Unit = {
    dataType match {
      case ut: UserType =>
        ut.classSpec = resolveUserType(curClass, ut.name)
      case SwitchType(_, cases) =>
        cases.foreach { case (_, ut) =>
          resolveUserType(curClass, ut)
        }
      case _ =>
        // not a user type, nothing to resolve
    }
  }

  def resolveUserType(curClass: ClassSpec, typeName: List[String]): Option[ClassSpec] = {
//    Console.println(s"resolveUserType: at ${curClass.name} doing ${typeName.mkString("|")}")
    val res = realResolveUserType(curClass, typeName)
//    Console.println("   => " + (res match {
//      case None => "???"
//      case Some(x) => x.name.mkString("|")
//    }))
    res
  }

  private def realResolveUserType(curClass: ClassSpec, typeName: List[String]): Option[ClassSpec] = {
    // First, try to do it in current class

    // If we're seeking composite name, we only have to resolve the very first
    // part of it at this stage
    val firstName :: restNames = typeName

    val resolvedHere = curClass.types.get(firstName).flatMap((nestedClass) =>
      if (restNames.isEmpty) {
        // No further names to resolve, here's our answer
        Some(nestedClass)
      } else {
        // Try to resolve recursively
        resolveUserType(nestedClass, restNames)
      }
    )

    resolvedHere match {
      case Some(_) => resolvedHere
      case None =>
        // No luck resolving here, let's try upper levels, if they exist
        curClass.upClass match {
          case Some(upClass) =>
            resolveUserType(upClass, typeName)
          case None =>
            // No luck at all
            None
        }
    }
  }

  val userTypes: Map[String, ClassSpec] = gatherUserTypes(topClass) ++ Map(topClassName.last -> topClass)

  var nowClass: ClassSpec = topClass

  def gatherUserTypes(curClass: ClassSpec): Map[String, ClassSpec] = {
    val recValues: Map[String, ClassSpec] = curClass.types.map {
      case (typeName, intClass) => gatherUserTypes(intClass)
    }.flatten.toMap
    curClass.types ++ recValues
  }

  def markupParentTypes(curClassName: List[String], curClass: ClassSpec): Unit = {
    curClass.seq.foreach { attr =>
      markupParentTypesAdd(curClassName, curClass, attr.dataType)
    }
    curClass.instances.foreach { case (instName, instSpec) =>
      markupParentTypesAdd(curClassName, curClass, getInstanceDataType(instSpec))
    }
  }

  def markupParentTypesAdd(curClassName: List[String], curClass: ClassSpec, dt: BaseType): Unit = {
    dt match {
      case userType: UserType =>
        val ut = userType.name
        userTypes.get(ut.last).foreach { usedClass =>
          usedClass._parentType match {
            case UnknownNamedClass =>
              usedClass._parentType = NamedClass(curClassName, curClass)
              markupParentTypes(curClassName ::: ut, usedClass)
            case NamedClass(otherName, otherClass) =>
              if (otherName == curClassName && otherClass == curClass) {
                // already done, don't do anything
              } else {
                // conflicting types, would be bad for statically typed languages
                // throw new RuntimeException(s"type '${attr.dataType}' has more than 1 conflicting parent types: ${otherName} and ${curClassName}")
                usedClass._parentType = GenericStructClass
              }
            case GenericStructClass =>
            // already most generic case, do nothing
          }
        }
      case _ => // ignore, it's standard type
    }
  }

  def deriveValueTypes() {
    userTypes.foreach { case (name, spec) => deriveValueType(spec) }
  }

  def deriveValueType(curClass: ClassSpec): Unit = {
    nowClass = curClass
    curClass.instances.foreach {
      case (instName, inst) =>
        inst match {
          case vi: ValueInstanceSpec =>
            vi.dataType = Some(lang.translator.detectType(vi.value))
          case _ =>
            // do nothing
        }
    }
  }

  override def compile {
    lang.open(topClassName.head, this)

    markupClassNames(topClass)
    deriveValueTypes
    resolveUserTypes(topClass)
    markupParentTypes(topClassName, topClass)

    lang.fileHeader(topClassName.head)
    compileClass(topClass)
    lang.fileFooter(topClassName.head)
    lang.close
  }

  def compileClass(curClass: ClassSpec): Unit = {
    nowClass = curClass

    lang.classHeader(nowClass.name)

    val extraAttrs = ListBuffer[AttrSpec]()
    extraAttrs += AttrSpec(RootIdentifier, UserTypeInstream(topClassName))
    extraAttrs += AttrSpec(ParentIdentifier, UserTypeInstream(curClass.parentTypeName))

    // Forward declarations for recursive types
    curClass.types.foreach { case (typeName, intClass) => lang.classForwardDeclaration(List(typeName)) }

    lang.classConstructorHeader(nowClass.name, curClass.parentTypeName, topClassName)
    curClass.instances.foreach { case (instName, instSpec) => lang.instanceClear(instName) }
    curClass.seq.foreach((attr) => lang.attrParse(attr, attr.id, extraAttrs, lang.normalIO))
    lang.classConstructorFooter

    lang.classDestructorHeader(nowClass.name, curClass.parentTypeName, topClassName)
    curClass.seq.foreach((attr) => lang.attrDestructor(attr, attr.id))
    curClass.instances.foreach { case (id, instSpec) =>
      instSpec match {
        case pis: ParseInstanceSpec => lang.attrDestructor(pis, id)
        case _: ValueInstanceSpec => // ignore for now
      }
    }
    lang.classDestructorFooter

    // Recursive types
    if (lang.innerClasses) {
      curClass.types.foreach { case (typeName, intClass) => compileClass(intClass) }

      nowClass = curClass
    }

    curClass.instances.foreach { case (instName, instSpec) => compileInstance(nowClass.name, instName, instSpec, extraAttrs) }

    // Attributes declarations and readers
    (curClass.seq ++ extraAttrs).foreach((attr) => lang.attributeDeclaration(attr.id, attr.dataTypeComposite, attr.cond))
    (curClass.seq ++ extraAttrs).foreach((attr) => lang.attributeReader(attr.id, attr.dataTypeComposite))

    curClass.enums.foreach { case(enumName, enumColl) => compileEnum(enumName, enumColl) }

    lang.classFooter(nowClass.name)

    if (!lang.innerClasses) {
      curClass.types.foreach { case (typeName, intClass) => compileClass(intClass) }
    }
  }

  def getInstanceDataType(instSpec: InstanceSpec): BaseType = {
    instSpec match {
      case t: ValueInstanceSpec => t.dataType.get
      case t: ParseInstanceSpec => t.dataTypeComposite
    }
  }

  def compileInstance(className: List[String], instName: InstanceIdentifier, instSpec: InstanceSpec, extraAttrs: ListBuffer[AttrSpec]): Unit = {
    // Determine datatype
    val dataType = getInstanceDataType(instSpec)

    // Declare caching variable
    lang.instanceDeclaration(instName, dataType, ConditionalSpec(None, NoRepeat))

    lang.instanceHeader(className, instName, dataType)
    lang.instanceCheckCacheAndReturn(instName)

    instSpec match {
      case ValueInstanceSpec(value, _) =>
        lang.instanceCalculate(instName, dataType, value)
      case i: ParseInstanceSpec =>
        val io = i.io match {
          case None => lang.normalIO
          case Some(ex) => lang.useIO(ex)
        }
        i.pos.foreach { pos =>
          lang.pushPos(io)
          lang.seek(io, pos)
        }
        lang.attrParse(i, instName, extraAttrs, io)
        i.pos.foreach((pos) => lang.popPos(io))
    }

    lang.instanceSetCalculated(instName)
    lang.instanceReturn(instName)
    lang.instanceFooter
  }

  override def determineType(attrName: String): BaseType = determineType(nowClass, attrName)

  override def determineType(typeName: List[String], attrName: String): BaseType = {
    getTypeByName(nowClass, typeName) match {
      case Some(t) => determineType(t, attrName)
      case None => throw new RuntimeException(s"Unable to determine type for $attrName in type $typeName")
    }
  }

  def determineType(classSpec: ClassSpec, attrName: String): BaseType = {
    attrName match {
      case "_root" =>
        UserTypeInstream(topClassName)
      case "_parent" =>
        UserTypeInstream(classSpec.parentTypeName)
      case "_io" =>
        KaitaiStreamType
      case "_" =>
        lang.currentIteratorType
      case "_on" =>
        lang.currentSwitchType
      case _ =>
        classSpec.seq.foreach { el =>
          if (el.id == NamedIdentifier(attrName))
            return el.dataTypeComposite
        }
        classSpec.instances.get(InstanceIdentifier(attrName)) match {
          case Some(i: ValueInstanceSpec) => return i.dataType.get
          case Some(i: ParseInstanceSpec) => return i.dataTypeComposite
          case None => // do nothing
        }
        throw new RuntimeException(s"Unable to access $attrName in ${classSpec.name} context")
    }
  }

  def getTypeByName(inClass: ClassSpec, name: List[String]): Option[ClassSpec] = {
    userTypes.get(name.last)

    // Some special code to support non-unique type names lookup - might come useful in future
//    if (name == topClassName)
//      return Some(desc)
//
//    if (inClass.types.isEmpty)
//      return None
//
//    val types = inClass.types.get
//    val r = types.get(name) match {
//      case Some(x) => Some(x)
//      case None => {
//        types.foreach { case (inTypesKey, inTypesValue) =>
//          if (inTypesValue != inClass) {
//            val r = getTypeByName(inTypesValue, name)
//            if (r.isDefined)
//              return r
//          }
//        }
//
//        // Look up global names list
//        userTypes.get(name)
//      }
//    }
  }

  def compileEnum(enumName: String, enumColl: Map[Long, String]): Unit = {
    lang.enumDeclaration(nowClass.name, enumName, enumColl)
  }
}

object ClassCompiler {
  def localFileToSpec(yamlFilename: String): ClassSpec = {
    val reader = new FileReader(yamlFilename)
    val mapper = new ObjectMapper(new YAMLFactory())
    mapper.readValue(reader, classOf[ClassSpec])
  }

  def fromLocalFileToFile(yamlFilename: String, lang: LanguageCompilerStatic, outDir: String, config: RuntimeConfig): AbstractCompiler =
    fromClassSpecToFile(localFileToSpec(yamlFilename), lang, outDir, config)

  def fromClassSpecToFile(topClass: ClassSpec, lang: LanguageCompilerStatic, outDir: String, config: RuntimeConfig): AbstractCompiler = {
    val outPath = lang.outFilePath(config, outDir, topClass.meta.get.id)
    if (config.verbose)
      Console.println(s"... => ${outPath}")
    lang match {
      case GraphvizClassCompiler =>
        val out = new FileLanguageOutputWriter(outPath, lang.indent)
        new GraphvizClassCompiler(topClass, out)
      case CppCompiler =>
        val outSrc = new FileLanguageOutputWriter(s"$outPath.cpp", lang.indent)
        val outHdr = new FileLanguageOutputWriter(s"$outPath.h", lang.indent)
        new ClassCompiler(topClass, new CppCompiler(config.verbose, outSrc, outHdr))
      case _ =>
        val out = new FileLanguageOutputWriter(outPath, lang.indent)
        new ClassCompiler(topClass, getCompiler(lang, config, out))
    }
  }

  def fromStringToString(src: String, lang: LanguageCompilerStatic, config: RuntimeConfig):
    (StringLanguageOutputWriter, Option[StringLanguageOutputWriter], ClassCompiler) = {
    val mapper = new ObjectMapper(new YAMLFactory())
    val topClass: ClassSpec = mapper.readValue(src, classOf[ClassSpec])

    fromClassSpecToString(topClass, lang, config)
  }

  def fromClassSpecToString(topClass: ClassSpec, lang: LanguageCompilerStatic, config: RuntimeConfig):
    (StringLanguageOutputWriter, Option[StringLanguageOutputWriter], ClassCompiler) = {
    lang match {
      case CppCompiler =>
        val outSrc = new StringLanguageOutputWriter(lang.indent)
        val outHdr = new StringLanguageOutputWriter(lang.indent)
        val cc = new ClassCompiler(topClass, new CppCompiler(config.verbose, outSrc, outHdr))
        (outSrc, Some(outHdr), cc)
      case _ =>
        val out = new StringLanguageOutputWriter(lang.indent)
        val cc = new ClassCompiler(topClass, getCompiler(lang, config, out))
        (out, None, cc)
    }
  }

  private def getCompiler(lang: LanguageCompilerStatic, config: RuntimeConfig, out: LanguageOutputWriter) = lang match {
    case CSharpCompiler => new CSharpCompiler(config.verbose, out, config.dotNetNamespace)
    case JavaCompiler => new JavaCompiler(config.verbose, out, config.javaPackage)
    case JavaScriptCompiler => new JavaScriptCompiler(config.verbose, out)
    case PerlCompiler => new PerlCompiler(config.verbose, out)
    case PHPCompiler => new PHPCompiler(config.verbose, out, config.phpNamespace)
    case PythonCompiler => new PythonCompiler(config.verbose, out)
    case RubyCompiler => new RubyCompiler(config.verbose, config.debug, out)
  }
}
