package org.vineflower.kotlin.struct;

import kotlinx.metadata.internal.metadata.ProtoBuf;
import kotlinx.metadata.internal.metadata.jvm.JvmProtoBuf;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.ClassesProcessor;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.rels.ClassWrapper;
import org.jetbrains.java.decompiler.main.rels.MethodWrapper;
import org.jetbrains.java.decompiler.modules.decompiler.exps.TypeAnnotation;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.util.InterpreterUtil;
import org.jetbrains.java.decompiler.util.TextBuffer;
import org.vineflower.kotlin.KotlinDecompilationContext;
import org.vineflower.kotlin.KotlinOptions;
import org.vineflower.kotlin.KotlinWriter;
import org.vineflower.kotlin.metadata.MetadataNameResolver;
import org.vineflower.kotlin.util.KUtils;
import org.vineflower.kotlin.util.ProtobufFlags;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record KFunction(
  String name,
  KParameter[] parameters,
  List<KTypeParameter> typeParameters,
  KType returnType,
  ProtobufFlags.Function flags,
  List<KType> contextReceiverTypes, MethodWrapper method,
  @Nullable KType receiverType,
  @Nullable KContract contract,
  boolean knownOverride,
  @NotNull DefaultArgsMap defaultArgs,
  ClassesProcessor.ClassNode node
) {
  public static Map<StructMethod, KFunction> parse(ClassesProcessor.ClassNode node) {
    MetadataNameResolver resolver = KotlinDecompilationContext.getNameResolver();
    ClassWrapper wrapper = node.getWrapper();
    StructClass struct = wrapper.getClassStruct();

    List<ProtoBuf.Function> protoFunctions;

    KotlinDecompilationContext.KotlinType type = KotlinDecompilationContext.getCurrentType();
    if (type == null) return Map.of();

    protoFunctions = switch (type) {
      case CLASS -> KotlinDecompilationContext.getCurrentClass().getFunctionList();
      case FILE -> KotlinDecompilationContext.getFilePackage().getFunctionList();
      case MULTIFILE_CLASS -> KotlinDecompilationContext.getMultifilePackage().getFunctionList();
      case SYNTHETIC_CLASS -> Collections.singletonList(KotlinDecompilationContext.getSyntheticClass()); // Lambdas and similar
    };

    Map<StructMethod, KFunction> functions = new HashMap<>(protoFunctions.size(), 1f);

    for (ProtoBuf.Function function : protoFunctions) {
      JvmProtoBuf.JvmMethodSignature jvmData = function.getExtension(JvmProtoBuf.methodSignature);

      ProtobufFlags.Function flags = new ProtobufFlags.Function(function.getFlags());

      String name = resolver.resolve(function.getName());

      KParameter[] parameters = new KParameter[function.getValueParameterCount()];
      for (int i = 0; i < parameters.length; i++) {
        ProtoBuf.ValueParameter parameter = function.getValueParameter(i);
        ProtobufFlags.ValueParameter paramFlags = new ProtobufFlags.ValueParameter(parameter.getFlags());
        String paramName = resolver.resolve(parameter.getName());
        KType paramType = KType.from(parameter.getType(), resolver);
        KType varargType = parameter.hasVarargElementType() ? KType.from(parameter.getVarargElementType(), resolver) : null;
        int typeId = parameter.getTypeId();
        parameters[i] = new KParameter(paramFlags, paramName, paramType, varargType, typeId);
      }

      KType receiverType = null;
      if (function.hasReceiverType()) {
        receiverType = KType.from(function.getReceiverType(), resolver);
      }

      KType returnType = KType.from(function.getReturnType(), resolver);

      MethodWrapper method = null;

      String lookupName = jvmData.hasName() ? resolver.resolve(jvmData.getName()) : name;
      if (jvmData.hasDesc()) {
        method = wrapper.getMethodWrapper(lookupName, resolver.resolve(jvmData.getDesc()));
      }

      if (method == null) {
        StringBuilder desc = new StringBuilder("(");
        if (receiverType != null) {
          desc.append(receiverType);
        }

        for (KParameter parameter : parameters) {
          desc.append(parameter.type());
        }

        desc.append(")").append(returnType);

        method = wrapper.getMethodWrapper(lookupName, desc.toString());

        if (method == null) {
          throw new IllegalStateException("Couldn't find method " + name + " " + desc + " in class " + struct.qualifiedName);
        }
      }

      List<KTypeParameter> typeParameters = function.getTypeParameterList().stream()
        .map(typeParameter -> KTypeParameter.from(typeParameter, resolver))
        .collect(Collectors.toList());

      List<KType> contextReceiverTypes = function.getContextReceiverTypeList().stream()
        .map(ctxType -> KType.from(ctxType, resolver))
        .collect(Collectors.toList());

      boolean isStatic = (method.methodStruct.getAccessFlags() & CodeConstants.ACC_STATIC) != 0;
      String defaultArgsName = name + "$default";
      StringBuilder defaultArgsDesc = new StringBuilder("(");
      if (!isStatic) {
        defaultArgsDesc.append("L").append(struct.qualifiedName).append(";");
      }
      if (receiverType != null) {
        defaultArgsDesc.append(receiverType);
      }
      for (KParameter parameter : parameters) {
        if (parameter.type().typeParameterName != null) {
          typeParameters.stream()
            .filter(typeParameter -> typeParameter.name().equals(parameter.type().typeParameterName))
            .findAny()
            .map(KTypeParameter::upperBounds)
            .filter(bounds -> bounds.size() == 1)
            .map(bounds -> bounds.get(0))
            .ifPresentOrElse(defaultArgsDesc::append, () -> defaultArgsDesc.append("Ljava/lang/Object;"));
        } else {
          defaultArgsDesc.append(parameter.type());
        }
      }

      defaultArgsDesc.append("I".repeat(parameters.length / 32 + 1));
      defaultArgsDesc.append("Ljava/lang/Object;)");
      defaultArgsDesc.append(returnType);

      DefaultArgsMap defaultArgs = DefaultArgsMap.from(wrapper.getMethodWrapper(defaultArgsName, defaultArgsDesc.toString()), method, parameters);

      boolean knownOverride = flags.visibility != ProtoBuf.Visibility.PRIVATE
        && flags.visibility != ProtoBuf.Visibility.PRIVATE_TO_THIS
        && flags.visibility != ProtoBuf.Visibility.LOCAL
        && KotlinWriter.searchForMethod(struct, method.methodStruct.getName(), method.desc(), false);

      KContract contract = function.hasContract() ? KContract.from(function.getContract(), List.of(parameters), resolver) : null;

      KFunction kFunction = new KFunction(
        name,
        parameters,
        typeParameters,
        returnType,
        flags,
        contextReceiverTypes,
        method,
        receiverType,
        contract,
        knownOverride,
        defaultArgs,
        node
      );

      functions.put(method.methodStruct, kFunction);
    }

    return functions;
  }

  public TextBuffer stringify(int indent) {
    TextBuffer buf = new TextBuffer();
    KotlinWriter.appendAnnotations(buf, indent, method.methodStruct, TypeAnnotation.METHOD_RETURN_TYPE);
    KotlinWriter.appendJvmAnnotations(buf, indent, method.methodStruct, false, method.classStruct.getPool(), TypeAnnotation.METHOD_RETURN_TYPE);

    String methodKey = InterpreterUtil.makeUniqueKey(method.methodStruct.getName(), method.methodStruct.getDescriptor());

    buf.appendIndent(indent);

    if (!contextReceiverTypes.isEmpty()) {
      buf.append("context(");
      boolean first = true;
      for (KType contextReceiverType : contextReceiverTypes) {
        if (!first) {
          buf.append(", ");
        }

        buf.append(contextReceiverType.stringify(indent + 1));
        first = false;
      }
      buf.append(")").appendLineSeparator().appendIndent(indent);
    }

    if (flags.visibility != ProtoBuf.Visibility.PUBLIC || DecompilerContext.getOption(KotlinOptions.SHOW_PUBLIC_VISIBILITY)) {
      KUtils.appendVisibility(buf, flags.visibility);
    }

    if (flags.isExpect) {
      buf.append("expect ");
    }

    if (flags.modality != ProtoBuf.Modality.FINAL) {
      if (!knownOverride || flags.modality != ProtoBuf.Modality.OPEN) {
        buf.append(flags.modality.name().toLowerCase())
          .append(' ');
      }
    }

    if (flags.isExternal) {
      buf.append("external ");
    }

    if (knownOverride) {
      buf.append("override ");
    }

    if (flags.isTailrec) {
      buf.append("tailrec ");
    }

    if (flags.isSuspend) {
      buf.append("suspend ");
    }

    if (flags.isInline) {
      buf.append("inline ");
    }

    if (flags.isInfix) {
      buf.append("infix ");
    }

    if (flags.isOperator) {
      buf.append("operator ");
    }

    buf.append("fun ");

    List<KTypeParameter> complexTypeParams = typeParameters.stream()
      .filter(typeParameter -> typeParameter.upperBounds().size() > 1)
      .toList();

    if (!typeParameters.isEmpty()) {
      buf.append('<');

      for (int i = 0; i < typeParameters.size(); i++) {
        KTypeParameter typeParameter = typeParameters.get(i);

        if (typeParameter.reified()) {
          buf.append("reified ");
        }

        buf.append(KotlinWriter.toValidKotlinIdentifier(typeParameter.name()));

        if (typeParameter.upperBounds().size() == 1) {
          buf.append(" : ").append(typeParameter.upperBounds().get(0).stringify(indent + 1));
        }

        if (i < typeParameters.size() - 1) {
          buf.append(", ");
        }
      }

      buf.append("> ");
    }

    if (receiverType != null) {
      // Function types need parentheses around the receiver type, but that happens in KType.stringify only if it's nullable
      // so we need to wrap in the case of non-nullable function types
      if (!receiverType.isNullable && receiverType.kotlinType.startsWith("kotlin/Function")) {
        buf.append("(");
      }
      buf.append(receiverType.stringify(indent + 1));
      if (!receiverType.isNullable && receiverType.kotlinType.startsWith("kotlin/Function")) {
        buf.append(")");
      }

      buf.append(".");
    }

    buf.append(KotlinWriter.toValidKotlinIdentifier(name))
      .append('(')
      .pushNewlineGroup(indent, 1)
      .appendPossibleNewline("");

    boolean first = true;
    for (KParameter parameter : parameters) {
      if (!first) {
        buf.append(",").appendPossibleNewline(" ");
      }

      first = false;

      parameter.stringify(indent + 1, buf);
      if (parameter.flags().declaresDefault) {
        buf.append(defaultArgs.toJava(parameter, indent + 1), node.classStruct.qualifiedName, methodKey);
      }
    }

    buf.appendPossibleNewline("", true)
      .popNewlineGroup()
      .append(')');

    if (returnType != null && returnType.type != VarType.VARTYPE_VOID.type) {
      buf.append(": ")
        .append(returnType.stringify(indent + 1));
    }

    if (complexTypeParams.isEmpty()) {
      buf.append(' ');
    } else {
      buf.pushNewlineGroup(indent, 1)
        .appendPossibleNewline(" ")
        .append("where ");

      first = true;
      for (KTypeParameter typeParameter : complexTypeParams) {
        for (KType upperBound : typeParameter.upperBounds()) {
          if (!first) {
            buf.appendPossibleNewline(",").appendPossibleNewline(" ");
          }

          buf.append(KotlinWriter.toValidKotlinIdentifier(typeParameter.name()))
            .append(" : ")
            .append(upperBound.stringify(indent + 1));

          first = false;
        }
      }

      buf.appendPossibleNewline(" ", true)
        .popNewlineGroup();
    }

    buf.append('{').appendLineSeparator();

    if (contract != null) {
      buf.append(contract.stringify(indent + 1));
    }

    RootStatement root = method.root;
    if (root != null && method.decompileError == null) {
      try {
        TextBuffer body = root.toJava(indent + 1);
        body.addBytecodeMapping(root.getDummyExit().bytecode);
        if (body.length() != 0 && contract != null) {
          buf.appendLineSeparator();
        }

        buf.append(body, node.classStruct.qualifiedName, methodKey);
      } catch (Throwable t) {
        String message = "Method " + method.methodStruct.getName() + " " + method.desc() + " in class " + node.classStruct.qualifiedName + " couldn't be written.";
        DecompilerContext.getLogger().writeMessage(message, IFernflowerLogger.Severity.WARN, t);
        method.decompileError = t;
      }
    }

    if (method.decompileError != null) {
      KotlinWriter.dumpError(buf, method, indent + 1);
    }

    buf.appendIndent(indent).append('}').appendLineSeparator();

    return buf;
  }
}
