/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.providers.MappingsProvider;
import net.fabricmc.loom.providers.MinecraftMappedProvider;
import net.fabricmc.loom.providers.MinecraftProvider;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;
import org.apache.commons.io.IOUtils;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public class ModProcessor {

	public static void handleMod(File input, File output, Project project){
		if(output.exists()){
			output.delete();
		}
		remapJar(input, output, project);

		JsonObject jsonObject = readInstallerJson(input);
		if(jsonObject != null){
			handleInstallerJson(jsonObject, project);
		}
	}

	private static void remapJar(File input, File output, Project project){
		LoomGradleExtension extension = project.getExtensions().getByType(LoomGradleExtension.class);
		String fromM = "intermediary";
		String toM = "named";

		MinecraftProvider minecraftProvider = extension.getMinecraftProvider();
		MinecraftMappedProvider mappedProvider = extension.getMinecraftMappedProvider();
		MappingsProvider mappingsProvider = extension.getMappingsProvider();

		File mappingsFile = mappingsProvider.MAPPINGS_TINY;
		Path mappings = mappingsFile.toPath();
		Path mc = mappedProvider.MINECRAFT_INTERMEDIARY_JAR.toPath();
		Path[] mcDeps = mappedProvider.getMapperPaths().stream()
			.map(File::toPath)
			.toArray(Path[]::new);
		Collection<File> modCompileFiles = project.getConfigurations().getByName(Constants.COMPILE_MODS).getFiles();
		Path[] modCompiles = modCompileFiles.stream()
				.map(File::toPath)
				.toArray(Path[]::new);

		project.getLogger().lifecycle(":remapping " + input.getName() + " (TinyRemapper, " + fromM + " -> " + toM + ")");

		TinyRemapper remapper = TinyRemapper.newRemapper()
			.withMappings(TinyUtils.createTinyMappingProvider(mappings, fromM, toM))
			.build();

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath(Paths.get(output.getAbsolutePath()))) {
			outputConsumer.addNonClassFiles(input.toPath());
			if (!modCompileFiles.contains(input)) {
				remapper.read(input.toPath());
			}
			remapper.read(modCompiles);
			remapper.read(mc);
			remapper.read(mcDeps);
			remapper.apply(input.toPath(), outputConsumer);
		} catch (Exception e){
			throw new RuntimeException("Failed to remap JAR to " + toM, e);
		} finally {
			remapper.finish();
		}

		if(!output.exists()){
			throw new RuntimeException("Failed to remap JAR to " + toM + " file not found: " + output.getAbsolutePath());
		}

		if (MixinRefmapHelper.transformRefmaps(remapper, output)) {
			project.getLogger().lifecycle(":remapping " + input.getName() + " (Mixin reference maps)");
			remapper.finish();
		}
	}

	private static void handleInstallerJson(JsonObject jsonObject, Project project){
		JsonObject libraries = jsonObject.get("libraries").getAsJsonObject();
		libraries.get("common").getAsJsonArray().forEach(jsonElement -> {
			String name = jsonElement.getAsJsonObject().get("name").getAsString();

			Configuration configuration = project.getConfigurations().getByName("compile");
			ExternalModuleDependency modDep = (ExternalModuleDependency) project.getDependencies().create(name);
			modDep.setTransitive(false);
			configuration.getDependencies().add(modDep);

			if(jsonElement.getAsJsonObject().has("url")){
				String url = jsonElement.getAsJsonObject().get("url").getAsString();
				long count = project.getRepositories().stream()
					.filter(artifactRepository -> artifactRepository instanceof MavenArtifactRepository)
					.map(artifactRepository -> (MavenArtifactRepository) artifactRepository)
					.filter(mavenArtifactRepository -> mavenArtifactRepository.getUrl().toString().equalsIgnoreCase(url)).count();
				if(count == 0){
					project.getRepositories().maven(mavenArtifactRepository -> mavenArtifactRepository.setUrl(jsonElement.getAsJsonObject().get("url").getAsString()));
				}

			}
		});
	}

	private static JsonObject readInstallerJson(File file){
		try {
			JarFile jarFile = new JarFile(file);
			ZipEntry entry = jarFile.getEntry("fabric-installer.json");
			if(entry == null){
				return null;
			}
			InputStream inputstream = jarFile.getInputStream(entry);
			String jsonStr = IOUtils.toString(inputstream, StandardCharsets.UTF_8);
			inputstream.close();
			jarFile.close();

			JsonObject jsonObject = new Gson().fromJson(jsonStr, JsonObject.class);
			return jsonObject;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

}
