package org.jmc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

import org.jmc.Options.OffsetType;
import org.jmc.util.Filesystem;
import org.jmc.util.Log;


/**
 * Handles the export of Minecraft world geometry to an .OBJ file (with matching .MTL file)
 */
public class ObjExporter
{

	/**
	 * Do the export.
	 * Export settings are taken from the global Options.
	 * 
	 * @param progress If not null, the exporter will invoke this callback to inform
	 * on the operation's progress.
	 * @param stop If not null, the exporter will poll this callback to check if the 
	 * operation should be cancelled.
	 * @param writeObj Whether to write the .obj file
	 * @param writeMtl Whether to write the .mtl file
	 */
	public static void export(ProgressCallback progress, StopCallback stop, boolean writeObj, boolean writeMtl)
	{
		File objfile=new File(Options.outputDir, Options.objFileName);
		File mtlfile=new File(Options.outputDir, Options.mtlFileName);
		File tmpdir=new File(Options.outputDir, "temp");

		if(tmpdir.exists())
		{
			Log.error("Cannot create directory: "+tmpdir.getAbsolutePath()+"\nSomething is in the way.", null);
			return;
		}

		try {
			objfile.createNewFile();
			mtlfile.createNewFile();
		} catch (IOException e) {
			Log.error("Cannot write to the chosen location!", e);
			return;
		}

		try
		{
			if(writeMtl)
			{
				Materials.copyMTLFile(mtlfile);
				Log.info("Saved materials to "+mtlfile.getAbsolutePath());
			}

			if(writeObj)
			{				
				PrintWriter obj_writer=new PrintWriter(new FileWriter(objfile));

				int cxs=(int)Math.floor(Options.minX/16.0f);
				int czs=(int)Math.floor(Options.minZ/16.0f);
				int cxe=(int)Math.ceil(Options.maxX/16.0f);
				int cze=(int)Math.ceil(Options.maxZ/16.0f);
				int oxs,oys,ozs;

				if(Options.offsetType==OffsetType.CENTER)
				{
					oxs=-(Options.minX+(Options.maxX-Options.minX)/2);
					oys=-Options.minY;
					ozs=-(Options.minZ+(Options.maxZ-Options.minZ)/2);
					Log.info("Center offset: "+oxs+"/"+oys+"/"+ozs);
				}
				else if(Options.offsetType==OffsetType.CUSTOM)
				{
					oxs=Options.offsetX;
					oys=0;
					ozs=Options.offsetZ;
					Log.info("Custom offset: "+oxs+"/"+oys+"/"+ozs);
				}
				else
				{
					oxs=0;
					oys=0;
					ozs=0;
				}

				if(Options.useUVFile)
				{
					Log.info("Using file to recalculate UVs: "+Options.UVFile.getAbsolutePath());
					try{
						UVRecalculate.load(Options.UVFile);
					}catch (Exception e) {
						Log.error("Cannot load UV file!", e);
						obj_writer.close();
						return;
					}
				}


				int progress_count=0;
				float progress_max=(cxe-cxs+1)*(cze-czs+1);

				ChunkDataBuffer chunk_buffer=new ChunkDataBuffer(Options.minX, Options.maxX, Options.minY, Options.maxY, Options.minZ, Options.maxZ);

				OBJOutputFile obj=new OBJOutputFile("minecraft");
				obj.setOffset(oxs, oys, ozs);
				obj.setScale(Options.scale);

				obj.appendMtl(obj_writer, mtlfile.getName());
				if(!Options.objectPerMaterial && !Options.objectPerBlock && !Options.objectPerChunk) obj.appendObjectname(obj_writer);

				Log.info("Processing chunks...");

				for(int cx=cxs; cx<=cxe; cx++)
				{
					for(int cz=czs; cz<=cze; cz++,progress_count++)
					{
						if (stop != null && stop.stopRequested())
							return;
						if (progress != null)
							progress.setProgress(progress_count / progress_max);					

						for(int lx=cx-1; lx<=cx+1; lx++)
							for(int lz=cz-1; lz<=cz+1; lz++)
							{
								if(lx<cxs || lx>cxe || lz<czs || lz>cze) continue;

								if(chunk_buffer.hasChunk(lx, lz)) continue;

								Chunk chunk=null;
								try{
									Region region=Region.findRegion(Options.worldDir, Options.dimension, lx, lz);							
									if(region==null) continue;					
									chunk=region.getChunk(lx, lz);
									if(chunk==null) continue;
								}catch (Exception e) {
									continue;
								}

								chunk_buffer.addChunk(chunk);									
							}

						obj.addChunkBuffer(chunk_buffer,cx,cz);
						obj.appendTextures(obj_writer);
						obj.appendNormals(obj_writer);
						obj.appendVertices(obj_writer);
						if(Options.objectPerChunk && !Options.objectPerBlock) obj_writer.println("g chunk_"+cx+"_"+cz);
						obj.appendFaces(obj_writer);
						obj.clearData(Options.removeDuplicates);

						for(int lx=cx-1; lx<=cx+1; lx++)
							chunk_buffer.removeChunk(lx,cz-1);
					}

					chunk_buffer.removeAllChunks();
				}

				obj_writer.close();

				if (progress != null)
					progress.setProgress(1);					
				Log.info("Saved model to "+objfile.getAbsolutePath());

				if(!Options.objectPerBlock)
				{

					Log.info("Sorting OBJ file...");

					if(!tmpdir.mkdir())
					{
						Log.error("Cannot temp create directory: "+tmpdir.getAbsolutePath(), null);
						return;
					}

					File mainfile=new File(tmpdir,"main");
					PrintWriter main=new PrintWriter(mainfile);
					File vertexfile=new File(tmpdir,"vertex");
					PrintWriter vertex=new PrintWriter(vertexfile);
					File normalfile=new File(tmpdir,"normal");
					PrintWriter normal=new PrintWriter(normalfile);
					File uvfile=new File(tmpdir,"uv");
					PrintWriter uv=new PrintWriter(uvfile);

					BufferedReader objin=new BufferedReader(new FileReader(objfile));				

					Map<String,FaceFile> faces=new HashMap<String, FaceFile>();
					int facefilecount=1;

					FaceFile current_ff=null;
					String current_g="g default";

					int maxcount=(int)objfile.length();
					if(maxcount==0) maxcount=1;
					int count=0;

					String line;
					while((line=objin.readLine())!=null)
					{
						if(line.length()==0) continue;

						count+=line.length()+1;
						if(count>maxcount) count=maxcount;

						if(progress!=null)
							progress.setProgress(0.5f*(float)count/(float)maxcount);

						if(line.startsWith("usemtl "))
						{
							line=line.substring(7).trim();						

							if(!faces.containsKey(line))
							{
								current_ff=new FaceFile();
								current_ff.name=line;
								current_ff.file=new File(tmpdir,""+facefilecount);
								facefilecount++;
								current_ff.writer=new PrintWriter(current_ff.file);
								faces.put(line, current_ff);
							}
							else
								current_ff=faces.get(line);

							if(Options.objectPerChunk)
							{
								current_ff.writer.println();
								current_ff.writer.println(current_g);
								current_ff.writer.println();
							}
						}
						else if(line.startsWith("f "))
						{
							if(current_ff!=null)
							{
								current_ff.writer.println(line);
							}
						}
						else if(line.startsWith("v "))
						{
							vertex.println(line);
						}	
						else if(line.startsWith("vn "))
						{
							normal.println(line);
						}	
						else if(line.startsWith("vt "))
						{
							uv.println(line);
						}	
						else if(line.startsWith("g "))
						{
							current_g=line;
						}
						else
						{						
							main.println(line);
							if(line.startsWith("mtllib"))
								main.println();
						}
					}

					objin.close();

					vertex.close();
					normal.close();
					uv.close();

					BufferedReader norm_reader=new BufferedReader(new FileReader(normalfile));
					while((line=norm_reader.readLine())!=null)
						main.println(line);
					norm_reader.close();
					normalfile.delete();

					BufferedReader uv_reader=new BufferedReader(new FileReader(uvfile));
					while((line=uv_reader.readLine())!=null)
						main.println(line);
					uv_reader.close();
					uvfile.delete();

					BufferedReader vertex_reader=new BufferedReader(new FileReader(vertexfile));
					while((line=vertex_reader.readLine())!=null)
						main.println(line);
					vertex_reader.close();
					vertexfile.delete();

					count=0;
					maxcount=faces.size();

					for(FaceFile ff:faces.values())
					{
						String current_mat=ff.name;

						ff.writer.close();

						count++;
						if(progress!=null)
							progress.setProgress(0.5f + 0.5f*(float)count/(float)maxcount);

						vertex.println();
						if(Options.objectPerMaterial && !Options.objectPerChunk) main.println("g "+ff.name);
						main.println();
						main.println("usemtl "+ff.name);
						main.println();

						BufferedReader reader=new BufferedReader(new FileReader(ff.file));
						while((line=reader.readLine())!=null)
						{
							if(Options.objectPerChunk && line.startsWith("g "))
							{
								if(Options.objectPerMaterial) main.println(line+"_"+current_mat);
								else main.println(line);
							}
							else
								main.println(line);
						}
						reader.close();

						ff.file.delete();
					}

					main.close();

					Filesystem.moveFile(mainfile, objfile);

					if (progress != null)
						progress.setProgress(1);					

					if(!tmpdir.delete())
						Log.error("Failed to erase temp dir: "+tmpdir.getAbsolutePath()+"\nPlease remove it yourself!", null);
				}
			}

			Log.info("Done!");

		}
		catch (Exception e) {
			Log.error("Error while exporting OBJ:", e);
		}
	}

}


/**
 * Little helper class for the map used in sorting.
 * @author danijel
 *
 */
class FaceFile
{
	public String name;
	public File file;
	public PrintWriter writer;
};
