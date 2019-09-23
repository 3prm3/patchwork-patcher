package net.coderbot.patchwork;

import com.electronwill.toml.Toml;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.coderbot.patchwork.annotation.AnnotationProcessor;
import net.coderbot.patchwork.annotation.ForgeAnnotations;
import net.coderbot.patchwork.manifest.converter.ModManifestConverter;
import net.coderbot.patchwork.manifest.forge.ModManifest;
import net.coderbot.patchwork.mapping.*;
import net.coderbot.patchwork.objectholder.AccessTransformPass;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.mappings.MappingsProvider;
import net.fabricmc.tinyremapper.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class Patchwork {
	public static void main(String[] args) throws Exception {
		Mappings intermediary = MappingsProvider.readTinyMappings(new FileInputStream(new File("data/mappings/intermediary-1.14.4.tiny")));
		List<TsrgClass<RawMapping>> classes = Tsrg.readMappings(new FileInputStream(new File("data/mappings/voldemap-1.14.4.tsrg")));

		IMappingProvider intermediaryMappings = TinyUtils.createTinyMappingProvider(Paths.get("data/mappings/intermediary-1.14.4.tiny"), "official", "intermediary");

		TsrgMappings mappings = new TsrgMappings(classes, intermediary, "official");
		String tiny = mappings.writeTiny("srg");

		Files.write(Paths.get("data/mappings/voldemap-1.14.4.tiny"), tiny.getBytes(StandardCharsets.UTF_8));

		// String mod = "BiomesOPlenty-1.14.4-9.0.0.253-universal";
		// String mod = "voyage-1.0.0";
		String mod = "bunchofbiomes-1.14.2-1.0.3";

		// System.out.println("Remapping Minecraft (official -> srg)");
		// remap(mappings, Paths.get("data/1.14.4+official.jar"), Paths.get("data/1.14.4+srg.jar"));

		System.out.println("Remapping " + mod + " (srg -> official)");
		remap(new InvertedTsrgMappings(mappings), Paths.get("data/" + mod + "+srg.jar"), Paths.get("data/" + mod + "+official.jar"), Paths.get("data/1.14.4+srg.jar"));

		System.out.println("Remapping " + mod + " (official -> intermediary)");
		remap(intermediaryMappings, Paths.get("data/" + mod + "+official.jar"), Paths.get("data/" + mod + "+intermediary.jar"), Paths.get("data/1.14.4+official.jar"));

		// Now scan for annotations, strip them, and replace them with pointers.

		Path input = Paths.get("data/" + mod + "+intermediary.jar");
		Path output = Paths.get("data/" + mod + "+transformed.jar");

		URI uri = new URI("jar:"+input.toUri().toString());
		FileSystem fs = null;
		boolean shouldClose = false;

		try {
			fs = FileSystems.getFileSystem(uri);
		} catch (FileSystemNotFoundException e) {

		}

		if (fs == null) {
			fs = FileSystems.newFileSystem(uri, Collections.emptyMap());
			shouldClose = true;
		}

		OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(output).build();
		outputConsumer.addNonClassFiles(input);

		Files.walkFileTree(fs.getPath("/"), new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				String name = file.toString();

				if (name.endsWith(".class")) {
					String baseName = name.substring(0, name.length() - ".class".length());
					byte[] content = Files.readAllBytes(file);

					ClassReader reader = new ClassReader(content);

					ClassNode node = new ClassNode();
					AnnotationProcessor scanner = new AnnotationProcessor(node);

					reader.accept(scanner, ClassReader.EXPAND_FRAMES);

					ForgeAnnotations annotations = scanner.getAnnotations();

					annotations.getMod().ifPresent(subscriber -> System.out.println("Class " + baseName + " has annotation: " + subscriber));
					annotations.getObjectHolderModId().ifPresent(modId -> System.out.println("Class " + baseName + " has object holder modId: " + modId));
					annotations.getSubscriber().ifPresent(subscriber -> System.out.println("Class " + baseName + " has annotation: " + subscriber));

					annotations.getSubscriptions().forEach(
							(method, subscription) ->
									System.out.println("Class " + baseName + " has annotation on method " + method + ": " + subscription)
					);

					Set<String> objectHolders = new HashSet<String>();

					annotations.getObjectHolders().forEach(
							(field, holder) -> {
								System.out.println("Class " + baseName + " has annotation on field " + field + ": " + holder);
								objectHolders.add(field);
							}
					);

					boolean global = annotations.getObjectHolderModId().isPresent();

					ClassWriter writer = new ClassWriter(0);
					AccessTransformPass pass = new AccessTransformPass(writer, global, objectHolders::contains);

					node.accept(pass);

					outputConsumer.accept(baseName, writer.toByteArray());
				}

				return FileVisitResult.CONTINUE;
			}
		});

		outputConsumer.close();

		if(shouldClose) {
			fs.close();
		}

		uri = new URI("jar:"+output.toUri().toString());
		fs = FileSystems.newFileSystem(uri, Collections.emptyMap());

		Path manifestPath = fs.getPath("/META-INF/mods.toml");
		String toml = new String(Files.readAllBytes(manifestPath), StandardCharsets.UTF_8);

		Map<String, Object> map = Toml.read(toml);

		System.out.println("Raw: " + map);

		ModManifest manifest = ModManifest.parse(map);

		System.out.println("Parsed: " + manifest);

		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		JsonObject fabric = ModManifestConverter.convertToFabric(manifest);
		String json = gson.toJson(fabric);

		Path fabricModJson = fs.getPath("/fabric.mod.json");

		System.out.println(fabricModJson);

		try {
			Files.delete(fabricModJson);
		} catch(IOException ignored) {}

		Files.write(fabricModJson, json.getBytes(StandardCharsets.UTF_8));

		System.out.println(json);

		Files.delete(manifestPath);
		Files.delete(fs.getPath("pack.mcmeta"));

		fs.close();

		// Late entrypoints
		// https://github.com/CottonMC/Cotton/blob/master/modules/cotton-datapack/src/main/java/io/github/cottonmc/cotton/datapack/mixins/MixinCottonInitializerServer.java
	}

	private static void remap(IMappingProvider mappings, Path input, Path output, Path... classpath) throws IOException {
		TinyRemapper remapper = TinyRemapper
				.newRemapper()
				.withMappings(mappings)
				.rebuildSourceFilenames(true)
				.build();


		OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(output).build();

		try {
			outputConsumer.addNonClassFiles(input, NonClassCopyMode.FIX_META_INF, remapper);

			remapper.readClassPath(classpath);
			remapper.readInputs(input);
			remapper.apply(outputConsumer);
		} finally {
			outputConsumer.close();
			remapper.finish();
		}
	}

}
